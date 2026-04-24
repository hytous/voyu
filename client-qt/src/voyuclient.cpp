#include "voyuclient.h"

#include <QDateTime>
#include <QMap>
#include <QJsonDocument>
#include <QJsonValue>
#include <QNetworkRequest>
#include <QSettings>
#include <QUrl>
#include <QUrlQuery>
#include <QUuid>

#include <algorithm>

namespace {
QString jsonKeyText(const QString &key) {
    QString text = key;
    text.replace('_', ' ');
    if (!text.isEmpty()) {
        text[0] = text[0].toUpper();
    }
    return text;
}

QString safeString(const QJsonObject &object, const QString &key) {
    return object.value(key).toString().trimmed();
}

QString clipText(const QString &value, int maxLength) {
    const QString normalized = value.trimmed();
    if (normalized.size() <= maxLength) {
        return normalized;
    }
    return normalized.left(maxLength - 1) + QStringLiteral("…");
}

QString extractReplyBodySummary(const QByteArray &body) {
    const QByteArray trimmed = body.trimmed();
    if (trimmed.isEmpty()) {
        return QString();
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(trimmed, &parseError);
    if (parseError.error == QJsonParseError::NoError && document.isObject()) {
        const QJsonObject object = document.object();
        QStringList parts;
        for (const QString &key : {QStringLiteral("message"),
                                   QStringLiteral("error"),
                                   QStringLiteral("path"),
                                   QStringLiteral("status")}) {
            const QString value = object.value(key).toString().trimmed();
            if (!value.isEmpty()) {
                parts.append(value);
            }
        }
        if (!parts.isEmpty()) {
            return clipText(parts.join(QStringLiteral(" | ")), 220);
        }
    }

    QString text = QString::fromUtf8(trimmed).trimmed();
    if (text.startsWith('<')) {
        return QString();
    }
    text.replace(QStringLiteral("\r"), QStringLiteral("\n"));
    text = text.simplified();
    return clipText(text, 220);
}

QString formatReplyError(QNetworkReply *reply, const QByteArray &body) {
    QStringList headerParts;
    const int httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (httpStatus > 0) {
        headerParts.append(QStringLiteral("HTTP %1").arg(httpStatus));
    }

    const QString reasonPhrase = reply->attribute(QNetworkRequest::HttpReasonPhraseAttribute).toString().trimmed();
    if (!reasonPhrase.isEmpty()) {
        headerParts.append(reasonPhrase);
    }

    const QString networkError = reply->errorString().trimmed();
    if (headerParts.isEmpty() && !networkError.isEmpty()) {
        headerParts.append(networkError);
    }

    const QString bodySummary = extractReplyBodySummary(body);
    if (bodySummary.isEmpty()) {
        return headerParts.join(QStringLiteral(" - "));
    }
    if (headerParts.isEmpty()) {
        return bodySummary;
    }
    return headerParts.join(QStringLiteral(" - ")) + QStringLiteral("\n") + bodySummary;
}

QJsonArray sortMessages(const QJsonArray &messages) {
    QList<QJsonObject> sorted;
    sorted.reserve(messages.size());
    for (const QJsonValue &value : messages) {
        sorted.append(value.toObject());
    }

    std::sort(sorted.begin(), sorted.end(), [](const QJsonObject &left, const QJsonObject &right) {
        const double leftSequence = left.value(QStringLiteral("sequence")).toDouble(-1);
        const double rightSequence = right.value(QStringLiteral("sequence")).toDouble(-1);
        if (leftSequence >= 0 && rightSequence >= 0 && leftSequence != rightSequence) {
            return leftSequence < rightSequence;
        }

        const QString leftCreatedAt = left.value(QStringLiteral("createdAt")).toString();
        const QString rightCreatedAt = right.value(QStringLiteral("createdAt")).toString();
        if (leftCreatedAt != rightCreatedAt) {
            return leftCreatedAt < rightCreatedAt;
        }

        return left.value(QStringLiteral("updatedAt")).toString() < right.value(QStringLiteral("updatedAt")).toString();
    });

    QJsonArray result;
    for (const QJsonObject &message : sorted) {
        result.append(message);
    }
    return result;
}

QJsonArray legacyTurnsToMessages(const QJsonArray &turns) {
    QJsonArray messages;
    qint64 sequence = 1;
    for (const QJsonValue &value : turns) {
        const QJsonObject turn = value.toObject();
        const QString timestamp = turn.value(QStringLiteral("timestamp")).toString();
        const QJsonObject requestSnapshot = turn.value(QStringLiteral("requestSnapshot")).toObject();
        const QString userMessage = turn.value(QStringLiteral("userMessage")).toString(
                requestSnapshot.value(QStringLiteral("message")).toString());
        const QString assistantMessage = turn.value(QStringLiteral("assistantMessage")).toString();
        const QString status = turn.value(QStringLiteral("status")).toString(QStringLiteral("COMPLETED"));

        if (!userMessage.trimmed().isEmpty()) {
            messages.append(QJsonObject{
                    {QStringLiteral("role"), QStringLiteral("USER")},
                    {QStringLiteral("content"), userMessage},
                    {QStringLiteral("messageType"), QStringLiteral("USER_INPUT")},
                    {QStringLiteral("status"), status},
                    {QStringLiteral("sequence"), static_cast<double>(sequence++)},
                    {QStringLiteral("createdAt"), timestamp},
                    {QStringLiteral("updatedAt"), timestamp},
                    {QStringLiteral("requestSnapshot"), requestSnapshot}
            });
        }
        if (!assistantMessage.trimmed().isEmpty()) {
            messages.append(QJsonObject{
                    {QStringLiteral("role"), QStringLiteral("ASSISTANT")},
                    {QStringLiteral("content"), assistantMessage},
                    {QStringLiteral("messageType"), turn.value(QStringLiteral("assistantMessageType")).toString(QStringLiteral("PLAN"))},
                    {QStringLiteral("status"), status},
                    {QStringLiteral("sequence"), static_cast<double>(sequence++)},
                    {QStringLiteral("createdAt"), timestamp},
                    {QStringLiteral("updatedAt"), timestamp}
            });
        }
    }
    return messages;
}

QJsonArray selectLatestTurnEvents(const QJsonArray &events) {
    if (events.isEmpty()) {
        return events;
    }

    int lastFinalAnswerIndex = -1;
    for (int index = 0; index < events.size(); ++index) {
        if (events.at(index).toObject().value(QStringLiteral("eventType")).toString() ==
            QStringLiteral("FINAL_ANSWER")) {
            lastFinalAnswerIndex = index;
        }
    }

    int startIndex = 0;
    if (lastFinalAnswerIndex >= 0) {
        if (lastFinalAnswerIndex == events.size() - 1) {
            for (int index = lastFinalAnswerIndex - 1; index >= 0; --index) {
                if (events.at(index).toObject().value(QStringLiteral("eventType")).toString() ==
                    QStringLiteral("FINAL_ANSWER")) {
                    startIndex = index + 1;
                    break;
                }
            }
        } else {
            startIndex = lastFinalAnswerIndex + 1;
        }
    }

    QJsonArray scoped;
    for (int index = startIndex; index < events.size(); ++index) {
        scoped.append(events.at(index));
    }
    return scoped;
}
}

VoyuClient::VoyuClient(QObject *parent) : QObject(parent) {
    m_streamRuntimeTimer.setInterval(1000);
    connect(&m_streamRuntimeTimer, &QTimer::timeout, this, [this]() {
        if (!m_streaming || m_streamStartedAtMs <= 0) {
            m_streamRuntimeTimer.stop();
            return;
        }
        rebuildDerivedState();
        emit sessionChanged();
    });
    loadRecentSessions();
    refreshSessions();
}

QString VoyuClient::baseUrl() const {
    return m_baseUrl;
}

void VoyuClient::setBaseUrl(const QString &baseUrl) {
    QString normalized = baseUrl.trimmed();
    while (normalized.endsWith('/')) {
        normalized.chop(1);
    }
    if (normalized.isEmpty() || normalized == m_baseUrl) {
        return;
    }
    m_baseUrl = normalized;
    emit baseUrlChanged();
}

QString VoyuClient::streamStatus() const {
    return m_streamStatus;
}

bool VoyuClient::streaming() const {
    return m_streaming;
}

QString VoyuClient::currentSessionId() const {
    return m_currentSessionId.isEmpty() ? QStringLiteral("未生成") : m_currentSessionId;
}

QString VoyuClient::activeSessionId() const {
    return m_currentSessionId;
}

QString VoyuClient::sessionStatus() const {
    return statusLabel(m_sessionStatusCode);
}

QString VoyuClient::userBrief() const {
    return m_userBrief;
}

QString VoyuClient::planThought() const {
    return m_planThought;
}

QString VoyuClient::mission() const {
    return m_mission;
}

QString VoyuClient::finalAnswer() const {
    return m_finalAnswer;
}

QString VoyuClient::updatedAtText() const {
    return m_updatedAtText;
}

QString VoyuClient::errorMessage() const {
    return m_errorMessage.isEmpty() ? QStringLiteral("无") : m_errorMessage;
}

QVariantMap VoyuClient::requestSnapshot() const {
    return m_requestSnapshot.toVariantMap();
}

QVariantList VoyuClient::requestChips() const {
    return m_requestChips;
}

QVariantList VoyuClient::conversationItems() const {
    return m_conversationItems;
}

QVariantList VoyuClient::reasoningItems() const {
    return m_reasoningItems;
}

QVariantList VoyuClient::taskItems() const {
    return m_taskItems;
}

QVariantList VoyuClient::knowledgeHits() const {
    return m_knowledgeHits;
}

QVariantList VoyuClient::timelineItems() const {
    return m_timelineItems;
}

QVariantList VoyuClient::infrastructureItems() const {
    return m_infrastructureItems;
}

QVariantList VoyuClient::recentSessions() const {
    return m_recentSessions;
}

void VoyuClient::startPlan(const QVariantMap &payload) {
    abortStream();

    QVariantMap request = payload;
    if (request.value(QStringLiteral("message")).toString().trimmed().isEmpty()) {
        setStreamStatus(QStringLiteral("请先填写旅行目标"));
        return;
    }

    if (request.value(QStringLiteral("sessionId")).toString().trimmed().isEmpty()) {
        request[QStringLiteral("sessionId")] = m_currentSessionId.isEmpty()
                ? QUuid::createUuid().toString(QUuid::WithoutBraces)
                : m_currentSessionId;
    }
    if (request.value(QStringLiteral("userId")).toString().trimmed().isEmpty()) {
        request[QStringLiteral("userId")] = QStringLiteral("voyu-qt");
    }

    QJsonObject snapshot = QJsonObject::fromVariantMap(request);
    m_streamStartedAtMs = QDateTime::currentMSecsSinceEpoch();
    resetSession(snapshot);
    appendLocalMessage(QStringLiteral("USER"),
                       snapshot.value(QStringLiteral("message")).toString(),
                       QStringLiteral("USER_INPUT"),
                       snapshot);

    m_streaming = true;
    m_streamRuntimeTimer.start();
    emit streamingChanged();
    setStreamStatus(QStringLiteral("规划中"));
    rebuildDerivedState();

    QNetworkRequest requestMessage(endpoint(QStringLiteral("/api/travel-agent/stream")));
    requestMessage.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    requestMessage.setRawHeader("Accept", "text/event-stream");

    m_streamReply = m_networkManager.post(requestMessage, QJsonDocument(snapshot).toJson(QJsonDocument::Compact));
    connect(m_streamReply, &QNetworkReply::readyRead, this, &VoyuClient::onStreamReadyRead);
    connect(m_streamReply, &QNetworkReply::finished, this, &VoyuClient::onStreamFinished);
}

void VoyuClient::loadSession(const QString &sessionId) {
    const QString normalized = sessionId.trimmed();
    if (normalized.isEmpty()) {
        return;
    }

    setStreamStatus(QStringLiteral("加载会话"));

    const QString encoded = QString::fromUtf8(QUrl::toPercentEncoding(normalized));
    QNetworkReply *reply = m_networkManager.get(
            QNetworkRequest(endpoint(QStringLiteral("/api/travel-agent/sessions/") + encoded + QStringLiteral("/chat"))));

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            setStreamStatus(QStringLiteral("加载失败"));
            return;
        }

        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll());
        if (!document.isObject()) {
            setStreamStatus(QStringLiteral("会话格式异常"));
            return;
        }

        hydrateFromDocument(document.object());
        upsertRecentSession();
        setStreamStatus(QStringLiteral("已加载"));
        refreshSessions();
    });
}

void VoyuClient::refreshInfrastructure() {
    QNetworkReply *reply = m_networkManager.get(
            QNetworkRequest(endpoint(QStringLiteral("/api/infrastructure/status"))));

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();

        QVariantList items;
        if (reply->error() != QNetworkReply::NoError) {
            items.append(QVariantMap{
                    {QStringLiteral("name"), QStringLiteral("infrastructure")},
                    {QStringLiteral("status"), QStringLiteral("DOWN")},
                    {QStringLiteral("message"), reply->errorString()},
                    {QStringLiteral("details"), QStringLiteral("请确认 nginx 与 voyu 服务可达")}
            });
            m_infrastructureItems = items;
            emit infrastructureChanged();
            return;
        }

        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll());
        const QJsonObject root = document.object();
        const QJsonObject components = root.value(QStringLiteral("components")).toObject();

        for (auto it = components.begin(); it != components.end(); ++it) {
            const QJsonObject component = it.value().toObject();
            const QJsonObject detailObject = component.value(QStringLiteral("details")).toObject();

            QStringList details;
            for (auto detailIt = detailObject.begin(); detailIt != detailObject.end(); ++detailIt) {
                details.append(QStringLiteral("%1=%2")
                                       .arg(jsonKeyText(detailIt.key()), summarizeValue(detailIt.value())));
            }

            items.append(QVariantMap{
                    {QStringLiteral("name"), it.key()},
                    {QStringLiteral("status"), component.value(QStringLiteral("up")).toBool() ? QStringLiteral("UP") : QStringLiteral("DOWN")},
                    {QStringLiteral("message"), component.value(QStringLiteral("message")).toString()},
                    {QStringLiteral("details"), details.join(QStringLiteral(" | "))}
            });
        }

        if (items.isEmpty()) {
            items.append(QVariantMap{
                    {QStringLiteral("name"), QStringLiteral("infrastructure")},
                    {QStringLiteral("status"), QStringLiteral("DOWN")},
                    {QStringLiteral("message"), QStringLiteral("未返回组件信息")},
                    {QStringLiteral("details"), QStringLiteral("")}
            });
        }

        m_infrastructureItems = items;
        emit infrastructureChanged();
    });
}

void VoyuClient::refreshSessions() {
    QUrl url = endpoint(QStringLiteral("/api/travel-agent/sessions"));
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("userId"), QStringLiteral("voyu-qt"));
    query.addQueryItem(QStringLiteral("limit"), QStringLiteral("20"));
    url.setQuery(query);

    QNetworkReply *reply = m_networkManager.get(QNetworkRequest(url));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            return;
        }

        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll());
        if (!document.isArray()) {
            return;
        }

        QVariantList sessions;
        for (const QJsonValue &value : document.array()) {
            const QJsonObject object = value.toObject();
            sessions.append(QVariantMap{
                    {QStringLiteral("sessionId"), object.value(QStringLiteral("sessionId")).toString()},
                    {QStringLiteral("title"), clip(object.value(QStringLiteral("title")).toString(), 48)},
                    {QStringLiteral("preview"), clip(object.value(QStringLiteral("preview")).toString(), 92)},
                    {QStringLiteral("destination"), object.value(QStringLiteral("destination")).toString()},
                    {QStringLiteral("travelDays"), object.value(QStringLiteral("travelDays")).toString()},
                    {QStringLiteral("status"), statusLabel(object.value(QStringLiteral("status")).toString())},
                    {QStringLiteral("updatedAt"), formatTimestamp(object.value(QStringLiteral("updatedAt")).toString())},
                    {QStringLiteral("messageCount"), object.value(QStringLiteral("messageCount")).toVariant()}
            });
        }

        m_recentSessions = sessions;
        saveRecentSessions();
        emit recentSessionsChanged();
    });
}

void VoyuClient::deleteSession(const QString &sessionId) {
    const QString normalized = sessionId.trimmed();
    if (normalized.isEmpty()) {
        return;
    }

    setStreamStatus(QStringLiteral("删除会话"));

    const QString encoded = QString::fromUtf8(QUrl::toPercentEncoding(normalized));
    QNetworkReply *reply = m_networkManager.sendCustomRequest(
            QNetworkRequest(endpoint(QStringLiteral("/api/travel-agent/sessions/") + encoded)),
            QByteArrayLiteral("DELETE"));

    connect(reply, &QNetworkReply::finished, this, [this, reply, normalized]() {
        reply->deleteLater();

        if (reply->error() != QNetworkReply::NoError) {
            setStreamStatus(QStringLiteral("删除失败"));
            return;
        }

        removeRecentSession(normalized);
        resetDraftState();
        saveRecentSessions();
        emit recentSessionsChanged();
        emit sessionChanged();
        setStreamStatus(QStringLiteral("已删除"));
        refreshSessions();
    });
}

void VoyuClient::startNewSession() {
    if (m_streamReply) {
        m_streamReply->abort();
    }
    m_streamRuntimeTimer.stop();
    m_streamStartedAtMs = 0;
    resetDraftState();
    setStreamStatus(QStringLiteral("新会话"));
}

void VoyuClient::abortStream() {
    if (m_streamReply) {
        m_streamReply->abort();
    }
}

QUrl VoyuClient::endpoint(const QString &path) const {
    QString normalizedBase = m_baseUrl;
    if (!normalizedBase.endsWith('/')) {
        normalizedBase += '/';
    }
    QUrl base(normalizedBase);
    QString normalizedPath = path;
    if (normalizedPath.startsWith('/')) {
        normalizedPath.remove(0, 1);
    }
    return base.resolved(QUrl(normalizedPath));
}

void VoyuClient::setStreamStatus(const QString &statusText) {
    if (m_streamStatus == statusText) {
        return;
    }
    m_streamStatus = statusText;
    emit streamStatusChanged();
}

void VoyuClient::resetSession(const QJsonObject &requestSnapshot) {
    const QString nextSessionId = requestSnapshot.value(QStringLiteral("sessionId")).toString();
    const bool continuingSession = !nextSessionId.isEmpty() && nextSessionId == m_currentSessionId;

    m_currentSessionId = nextSessionId;
    m_requestSnapshot = requestSnapshot;
    m_events = QJsonArray();
    if (!continuingSession) {
        m_messages = QJsonArray();
        m_turns = QJsonArray();
    }

    m_documentStatus = QStringLiteral("RUNNING");
    m_documentFinalAnswer.clear();
    m_documentErrorMessage.clear();
    m_documentUpdatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    m_documentCompletedAt.clear();

    rebuildDerivedState();
    upsertRecentSession();
}

void VoyuClient::resetDraftState() {
    m_streamRuntimeTimer.stop();
    m_streamStartedAtMs = 0;
    m_currentSessionId.clear();
    m_requestSnapshot = QJsonObject();
    m_events = QJsonArray();
    m_messages = QJsonArray();
    m_turns = QJsonArray();

    m_documentStatus = QStringLiteral("DRAFT");
    m_documentFinalAnswer.clear();
    m_documentErrorMessage.clear();
    m_documentUpdatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    m_documentCompletedAt.clear();

    rebuildDerivedState();
}

void VoyuClient::appendLocalMessage(const QString &role,
                                    const QString &content,
                                    const QString &messageType,
                                    const QJsonObject &requestSnapshot) {
    const QString normalizedContent = content.trimmed();
    if (normalizedContent.isEmpty()) {
        return;
    }

    if (m_messages.isEmpty() && !m_turns.isEmpty()) {
        m_messages = legacyTurnsToMessages(m_turns);
    }

    const QString timestamp = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    QJsonObject message{
            {QStringLiteral("role"), role},
            {QStringLiteral("content"), normalizedContent},
            {QStringLiteral("messageType"), messageType},
            {QStringLiteral("status"), QStringLiteral("COMPLETED")},
            {QStringLiteral("sequence"), nextLocalSequence()},
            {QStringLiteral("createdAt"), timestamp},
            {QStringLiteral("updatedAt"), timestamp}
    };
    if (!requestSnapshot.isEmpty()) {
        message.insert(QStringLiteral("requestSnapshot"), requestSnapshot);
    }

    m_messages.append(message);
    m_messages = sortMessages(m_messages);
    m_documentUpdatedAt = timestamp;
}

void VoyuClient::hydrateFromDocument(const QJsonObject &document) {
    m_streamRuntimeTimer.stop();
    m_streamStartedAtMs = 0;
    m_currentSessionId = document.value(QStringLiteral("sessionId")).toString();
    const QJsonObject lastRequestSnapshot = document.value(QStringLiteral("lastRequestSnapshot")).toObject();
    m_requestSnapshot = lastRequestSnapshot.isEmpty()
            ? document.value(QStringLiteral("requestSnapshot")).toObject()
            : lastRequestSnapshot;
    const QJsonArray traceEvents = document.value(QStringLiteral("traceEvents")).toArray();
    m_events = sortEvents(traceEvents.isEmpty() ? document.value(QStringLiteral("events")).toArray() : traceEvents);
    m_messages = sortMessages(document.value(QStringLiteral("messages")).toArray());
    m_turns = document.value(QStringLiteral("turns")).toArray();

    if (m_requestSnapshot.isEmpty() && !m_messages.isEmpty()) {
        for (int index = m_messages.size() - 1; index >= 0; --index) {
            const QJsonObject message = m_messages.at(index).toObject();
            if (message.value(QStringLiteral("role")).toString() != QStringLiteral("USER")) {
                continue;
            }
            const QJsonObject requestSnapshot = message.value(QStringLiteral("requestSnapshot")).toObject();
            if (!requestSnapshot.isEmpty()) {
                m_requestSnapshot = requestSnapshot;
                break;
            }
        }
    }

    m_documentStatus = document.value(QStringLiteral("status")).toString();
    m_documentFinalAnswer = document.value(QStringLiteral("finalAnswer")).toString();
    m_documentErrorMessage = document.value(QStringLiteral("errorMessage")).toString();
    m_documentUpdatedAt = document.value(QStringLiteral("updatedAt")).toString();
    m_documentCompletedAt = document.value(QStringLiteral("completedAt")).toString();

    rebuildDerivedState();
}

void VoyuClient::rebuildDerivedState() {
    QVariantList chips;
    const QList<QPair<QString, QString>> summaryKeys = {
            {QStringLiteral("destination"), QStringLiteral("目的地")},
            {QStringLiteral("departure"), QStringLiteral("出发地")},
            {QStringLiteral("travelDays"), QStringLiteral("行程天数")},
            {QStringLiteral("budget"), QStringLiteral("预算")},
            {QStringLiteral("preferences"), QStringLiteral("偏好")},
            {QStringLiteral("userId"), QStringLiteral("用户标识")}
    };

    for (const auto &entry : summaryKeys) {
        const QString value = m_requestSnapshot.value(entry.first).toString().trimmed();
        if (!value.isEmpty()) {
            chips.append(QVariantMap{
                    {QStringLiteral("label"), entry.second},
                    {QStringLiteral("value"), value}
            });
        }
    }

    QString computedStatus = m_documentStatus.isEmpty() ? QStringLiteral("DRAFT") : m_documentStatus;
    QString planThoughtText = QStringLiteral("等待规划器给出任务拆解。");
    QString missionText = QStringLiteral("任务书生成后，这里会展示串行批次与 <sep> 并行分隔。");
    QString finalAnswerText = m_documentFinalAnswer;
    QString errorText = m_documentErrorMessage;
    QJsonObject latestKnowledgePayload;
    QString latestResponseKind = QStringLiteral("PLAN");
    QString pendingAssistantKind = QStringLiteral("STATUS");
    QString pendingAssistantText = QStringLiteral("思考中，正在分析你的需求并组织工具执行计划。");
    QString pendingAssistantElapsed;
    QVariantList conversation;
    QVariantList reasoning;
    QVariantList timeline;
    QMap<QString, QVariantMap> runtimeRows;
    const QJsonArray persistedMessages = m_messages.isEmpty() ? legacyTurnsToMessages(m_turns) : m_messages;

    for (const QJsonValue &value : persistedMessages) {
        const QJsonObject message = value.toObject();
        const QString role = message.value(QStringLiteral("role")).toString();
        const QJsonObject requestSnapshot = message.value(QStringLiteral("requestSnapshot")).toObject();
        const QString body = message.value(QStringLiteral("content")).toString(
                requestSnapshot.value(QStringLiteral("message")).toString());
        const QString timestamp = formatTimestamp(message.value(QStringLiteral("createdAt")).toString(
                message.value(QStringLiteral("updatedAt")).toString()));

        if (body.trimmed().isEmpty()) {
            continue;
        }

        if (role == QStringLiteral("USER")) {
            conversation.append(QVariantMap{
                    {QStringLiteral("label"), QStringLiteral("用户")},
                    {QStringLiteral("kind"), QStringLiteral("USER")},
                    {QStringLiteral("body"), body},
                    {QStringLiteral("time"), timestamp},
                    {QStringLiteral("elapsed"), QString()}
            });
        } else {
            conversation.append(QVariantMap{
                    {QStringLiteral("label"), QStringLiteral("助手")},
                    {QStringLiteral("kind"), message.value(QStringLiteral("messageType")).toString(QStringLiteral("PLAN"))},
                    {QStringLiteral("body"), body},
                    {QStringLiteral("time"), timestamp},
                    {QStringLiteral("elapsed"), QString()}
            });
        }
    }

    const QJsonArray orderedEvents = selectLatestTurnEvents(sortEvents(m_events));
    if (m_streamStartedAtMs > 0 && (m_streaming || computedStatus == QStringLiteral("RUNNING"))) {
        pendingAssistantElapsed = formatElapsedDuration(QDateTime::currentMSecsSinceEpoch() - m_streamStartedAtMs);
    }
    int sequence = 0;
    for (const QJsonValue &value : orderedEvents) {
        const QJsonObject event = value.toObject();
        const QString eventType = event.value(QStringLiteral("eventType")).toString();
        const QJsonObject payload = event.value(QStringLiteral("payload")).toObject();
        const int round = event.value(QStringLiteral("round")).toInt(payload.value(QStringLiteral("round")).toInt());
        const QString timestamp = formatTimestamp(event.value(QStringLiteral("timestamp")).toString());
        const QString summary = summarizeEvent(eventType, payload);

        timeline.append(QVariantMap{
                {QStringLiteral("label"), eventLabel(eventType)},
                {QStringLiteral("body"), summary},
                {QStringLiteral("time"), timestamp},
                {QStringLiteral("tone"), eventType == QStringLiteral("WARNING") ? QStringLiteral("warning")
                                                                               : (eventType == QStringLiteral("FINAL_ANSWER") ? QStringLiteral("success") : QStringLiteral("normal"))}
        });

        if (eventType == QStringLiteral("MEMORY") ||
            eventType == QStringLiteral("THOUGHT") ||
            eventType == QStringLiteral("PLAN_DRAFT") ||
            eventType == QStringLiteral("TASK_BOOK") ||
            eventType == QStringLiteral("TOOL_CALL") ||
            eventType == QStringLiteral("TOOL_RESULT") ||
            eventType == QStringLiteral("WARNING")) {
            reasoning.append(QVariantMap{
                    {QStringLiteral("label"), eventLabel(eventType)},
                    {QStringLiteral("body"), summary},
                    {QStringLiteral("time"), timestamp}
            });
        }

        if (eventType == QStringLiteral("MEMORY")) {
            const QString summaryText = payload.value(QStringLiteral("summary")).toString();
            if (!summaryText.isEmpty()) {
                planThoughtText = summaryText;
                pendingAssistantText = QStringLiteral("思考中，正在读取会话记忆并整理上下文。");
            }
        } else if (eventType == QStringLiteral("THOUGHT")) {
            planThoughtText = payload.value(QStringLiteral("message")).toString(planThoughtText);
            computedStatus = QStringLiteral("RUNNING");
            pendingAssistantKind = QStringLiteral("STATUS");
            pendingAssistantText = planThoughtText;
        } else if (eventType == QStringLiteral("PLAN_DRAFT")) {
            const QString thought = payload.value(QStringLiteral("thought")).toString();
            if (!thought.isEmpty()) {
                planThoughtText = thought;
                pendingAssistantKind = QStringLiteral("STATUS");
                pendingAssistantText = QStringLiteral("思考中，正在修正工具执行计划：") + thought;
            }
        } else if (eventType == QStringLiteral("TASK_BOOK")) {
            const QString mission = payload.value(QStringLiteral("mission")).toString();
            if (!mission.isEmpty()) {
                const QString scriptText = formatTaskScript(payload);
                missionText = scriptText.isEmpty()
                        ? mission
                        : mission + QStringLiteral("\n\n任务书排布：\n") + scriptText;
            }
            pendingAssistantKind = QStringLiteral("STATUS");
            pendingAssistantText = QStringLiteral("已生成工具执行计划，正在按批次执行任务。");
        } else if (eventType == QStringLiteral("TASK_STATUS")) {
            const QString taskId = payload.value(QStringLiteral("taskId")).toString();
            const QString phase = payload.value(QStringLiteral("phase")).toString();
            const int batchIndex = payload.value(QStringLiteral("batchIndex")).toInt(0);
            const int attempt = payload.value(QStringLiteral("attempt")).toInt(0);
            const int step = payload.value(QStringLiteral("step")).toInt(0);
            const QString rawStatus = payload.value(QStringLiteral("status")).toString(QStringLiteral("UNKNOWN"));
            const QString taskName = payload.value(QStringLiteral("taskName")).toString(taskId);
            const QString key = phase + QStringLiteral(":") + QString::number(round) + QStringLiteral(":") + taskId;

            QVariantMap row = runtimeRows.value(key);
            if (row.isEmpty()) {
                row.insert(QStringLiteral("taskId"), taskId);
                row.insert(QStringLiteral("title"), payload.value(QStringLiteral("taskName")).toString(taskId));
                row.insert(QStringLiteral("sortRound"), round);
                row.insert(QStringLiteral("sortBatch"), batchIndex);
                row.insert(QStringLiteral("sortSource"), phase == QStringLiteral("PLAN") ? 0 : 1);
                row.insert(QStringLiteral("sortAttempt"), attempt);
                row.insert(QStringLiteral("sortSequence"), sequence++);
            }

            row[QStringLiteral("source")] = sourceLabel(phase);
            row[QStringLiteral("roundAttempt")] = phase == QStringLiteral("PLAN")
                    ? (step > 0
                               ? QStringLiteral("第%1轮 / Step %2").arg(round).arg(step)
                               : QStringLiteral("第%1轮").arg(round))
                    : (attempt > 0
                               ? QStringLiteral("第%1轮 / 第%2次").arg(round).arg(attempt)
                               : QStringLiteral("第%1轮").arg(round));
            row[QStringLiteral("batch")] = batchIndex > 0 ? QStringLiteral("B%1").arg(batchIndex) : QStringLiteral("-");
            row[QStringLiteral("mode")] = modeLabel(payload.value(QStringLiteral("executionMode")).toString());
            row[QStringLiteral("status")] = statusLabel(payload.value(QStringLiteral("status")).toString(QStringLiteral("UNKNOWN")));
            row[QStringLiteral("description")] = QStringLiteral("%1 | 工具: %2 | 任务ID: %3").arg(
                    payload.value(QStringLiteral("taskName")).toString(taskId),
                    payload.value(QStringLiteral("toolName")).toString(),
                    taskId);
            row[QStringLiteral("sortAttempt")] = attempt;
            row[QStringLiteral("sortBatch")] = batchIndex;
            runtimeRows.insert(key, row);

            if (rawStatus == QStringLiteral("RETRYING")) {
                pendingAssistantKind = QStringLiteral("RETRYING");
                pendingAssistantText = QStringLiteral("重试中，正在调整后重新执行任务：%1").arg(taskName);
            } else if (rawStatus == QStringLiteral("RUNNING")) {
                pendingAssistantKind = QStringLiteral("STATUS");
                pendingAssistantText = phase == QStringLiteral("PLAN")
                        ? QStringLiteral("思考中，正在完善工具执行计划：%1").arg(taskName)
                        : QStringLiteral("执行中，正在处理任务：%1").arg(taskName);
            } else if (rawStatus == QStringLiteral("DONE")) {
                pendingAssistantKind = QStringLiteral("STATUS");
                pendingAssistantText = QStringLiteral("已完成任务：%1，正在继续整理结果。").arg(taskName);
            } else if (rawStatus == QStringLiteral("FAILED")) {
                pendingAssistantKind = QStringLiteral("ERROR");
                pendingAssistantText = QStringLiteral("任务失败：%1").arg(taskName);
            }
        } else if (eventType == QStringLiteral("TOOL_RESULT") &&
                   payload.value(QStringLiteral("toolName")).toString() == QStringLiteral("rag.travel.knowledge")) {
            latestKnowledgePayload = payload;
        } else if (eventType == QStringLiteral("FINAL_ANSWER")) {
            const QString answer = payload.value(QStringLiteral("answer")).toString();
            if (!answer.isEmpty()) {
                finalAnswerText = answer;
            }
             latestResponseKind = payload.value(QStringLiteral("responseKind")).toString(QStringLiteral("PLAN"));
            computedStatus = QStringLiteral("COMPLETED");
        } else if (eventType == QStringLiteral("WARNING")) {
            const QString warningMessage = payload.value(QStringLiteral("message")).toString();
            if (!warningMessage.isEmpty()) {
                errorText = warningMessage;
                pendingAssistantKind = QStringLiteral("ERROR");
                pendingAssistantText = warningMessage;
            }
            if (computedStatus != QStringLiteral("COMPLETED")) {
                computedStatus = QStringLiteral("FAILED");
            }
        }
    }

    QList<QVariantMap> allTaskRows;
    for (auto it = runtimeRows.begin(); it != runtimeRows.end(); ++it) {
        allTaskRows.append(it.value());
    }

    std::sort(allTaskRows.begin(), allTaskRows.end(), [](const QVariantMap &left, const QVariantMap &right) {
        if (left.value(QStringLiteral("sortRound")).toInt() != right.value(QStringLiteral("sortRound")).toInt()) {
            return left.value(QStringLiteral("sortRound")).toInt() < right.value(QStringLiteral("sortRound")).toInt();
        }
        if (left.value(QStringLiteral("sortBatch")).toInt() != right.value(QStringLiteral("sortBatch")).toInt()) {
            return left.value(QStringLiteral("sortBatch")).toInt() < right.value(QStringLiteral("sortBatch")).toInt();
        }
        if (left.value(QStringLiteral("sortSource")).toInt() != right.value(QStringLiteral("sortSource")).toInt()) {
            return left.value(QStringLiteral("sortSource")).toInt() < right.value(QStringLiteral("sortSource")).toInt();
        }
        if (left.value(QStringLiteral("sortAttempt")).toInt() != right.value(QStringLiteral("sortAttempt")).toInt()) {
            return left.value(QStringLiteral("sortAttempt")).toInt() < right.value(QStringLiteral("sortAttempt")).toInt();
        }
        return left.value(QStringLiteral("sortSequence")).toInt() < right.value(QStringLiteral("sortSequence")).toInt();
    });

    QVariantList tasks;
    for (const QVariantMap &row : allTaskRows) {
        tasks.append(row);
    }

    QString latestTurnUser;
    QString latestTurnAssistant;
    for (int index = persistedMessages.size() - 1; index >= 0; --index) {
        const QJsonObject message = persistedMessages.at(index).toObject();
        const QString role = message.value(QStringLiteral("role")).toString();
        const QString body = message.value(QStringLiteral("content")).toString(
                message.value(QStringLiteral("requestSnapshot")).toObject().value(QStringLiteral("message")).toString());
        if (body.trimmed().isEmpty()) {
            continue;
        }
        if (latestTurnUser.isEmpty() && role == QStringLiteral("USER")) {
            latestTurnUser = body;
        } else if (latestTurnAssistant.isEmpty() && role == QStringLiteral("ASSISTANT")) {
            latestTurnAssistant = body;
            latestResponseKind = message.value(QStringLiteral("messageType")).toString(latestResponseKind);
        }
        if (!latestTurnUser.isEmpty() && !latestTurnAssistant.isEmpty()) {
            break;
        }
    }

    const QString currentUserMessage = m_requestSnapshot.value(QStringLiteral("message")).toString().trimmed();
    if (!currentUserMessage.isEmpty() &&
        (persistedMessages.isEmpty() || latestTurnUser != currentUserMessage)) {
        conversation.append(QVariantMap{
                {QStringLiteral("label"), QStringLiteral("用户")},
                {QStringLiteral("kind"), QStringLiteral("USER")},
                {QStringLiteral("body"), currentUserMessage},
                {QStringLiteral("time"), m_updatedAtText},
                {QStringLiteral("elapsed"), QString()}
        });
    }
    if (!finalAnswerText.trimmed().isEmpty() &&
        (persistedMessages.isEmpty() || latestTurnAssistant != finalAnswerText)) {
        conversation.append(QVariantMap{
                {QStringLiteral("label"), QStringLiteral("助手")},
                {QStringLiteral("kind"), latestResponseKind},
                {QStringLiteral("body"), finalAnswerText},
                {QStringLiteral("time"), m_updatedAtText},
                {QStringLiteral("elapsed"), QString()}
        });
    } else if (computedStatus == QStringLiteral("FAILED")) {
        const QString failureText = errorText.trimmed().isEmpty()
                ? QStringLiteral("本轮处理失败，请重试。")
                : errorText;
        conversation.append(QVariantMap{
                {QStringLiteral("label"), QStringLiteral("助手")},
                {QStringLiteral("kind"), QStringLiteral("ERROR")},
                {QStringLiteral("body"), failureText},
                {QStringLiteral("time"), formatTimestamp(m_documentUpdatedAt)},
                {QStringLiteral("elapsed"), QString()}
        });
    } else if (m_streaming) {
        conversation.append(QVariantMap{
                {QStringLiteral("label"), QStringLiteral("助手")},
                {QStringLiteral("kind"), pendingAssistantKind},
                {QStringLiteral("body"), pendingAssistantText},
                {QStringLiteral("time"), formatTimestamp(m_documentUpdatedAt)},
                {QStringLiteral("elapsed"), pendingAssistantElapsed}
        });
    }

    QVariantList knowledge;
    const QJsonArray hits = latestKnowledgePayload.value(QStringLiteral("result")).toObject().value(QStringLiteral("hits")).toArray();
    int hitIndex = 1;
    for (const QJsonValue &hitValue : hits) {
        const QJsonObject hit = hitValue.toObject();
        const QString title = hit.value(QStringLiteral("title")).toString(
                hit.value(QStringLiteral("name")).toString(
                        hit.value(QStringLiteral("source")).toString(QStringLiteral("知识片段 %1").arg(hitIndex))));
        QString excerpt = hit.value(QStringLiteral("snippet")).toString();
        if (excerpt.isEmpty()) {
            excerpt = hit.value(QStringLiteral("content")).toString();
        }
        if (excerpt.isEmpty()) {
            excerpt = hit.value(QStringLiteral("text")).toString();
        }
        if (excerpt.isEmpty()) {
            excerpt = summarizeValue(hitValue);
        }
        knowledge.append(QVariantMap{
                {QStringLiteral("title"), title},
                {QStringLiteral("source"), hit.value(QStringLiteral("source")).toString(hit.value(QStringLiteral("id")).toString())},
                {QStringLiteral("score"), hit.value(QStringLiteral("score")).toVariant()},
                {QStringLiteral("excerpt"), clip(excerpt, 240)}
        });
        ++hitIndex;
    }

    QString brief = m_requestSnapshot.value(QStringLiteral("message")).toString().trimmed();
    if (brief.isEmpty()) {
        brief = latestTurnUser;
    }
    if (brief.isEmpty()) {
        brief = QStringLiteral("还没有开始新的规划，请先输入你的旅行目标。");
    }

    if (finalAnswerText.isEmpty() && computedStatus == QStringLiteral("COMPLETED")) {
        finalAnswerText = QStringLiteral("执行已完成，但当前没有返回最终文本。");
    }

    if (m_documentUpdatedAt.isEmpty()) {
        m_documentUpdatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    }

    m_sessionStatusCode = computedStatus;
    m_userBrief = brief;
    m_planThought = planThoughtText;
    m_mission = missionText;
    m_finalAnswer = finalAnswerText;
    m_errorMessage = errorText;
    m_requestChips = chips;
    m_conversationItems = conversation;
    m_reasoningItems = reasoning;
    m_taskItems = tasks;
    m_knowledgeHits = knowledge;
    m_timelineItems = timeline;
    m_updatedAtText = formatTimestamp(m_documentUpdatedAt);

    emit sessionChanged();
}

void VoyuClient::ingestEvent(const QJsonObject &event) {
    const QString sessionId = event.value(QStringLiteral("sessionId")).toString();
    if (!sessionId.isEmpty()) {
        m_currentSessionId = sessionId;
    }

    const QString eventType = event.value(QStringLiteral("eventType")).toString();
    const QJsonObject payload = event.value(QStringLiteral("payload")).toObject();
    if (eventType == QStringLiteral("MEMORY")) {
        for (const QString &key : {QStringLiteral("destination"),
                                   QStringLiteral("departure"),
                                   QStringLiteral("travelDays"),
                                   QStringLiteral("budget"),
                                   QStringLiteral("preferences")}) {
            const QString value = payload.value(key).toString().trimmed();
            if (!value.isEmpty()) {
                m_requestSnapshot.insert(key, value);
            }
        }
    } else if (eventType == QStringLiteral("FINAL_ANSWER")) {
        appendLocalMessage(QStringLiteral("ASSISTANT"),
                           payload.value(QStringLiteral("answer")).toString(),
                           payload.value(QStringLiteral("responseKind")).toString(QStringLiteral("PLAN")));
    }

    m_events.append(event);
    m_documentUpdatedAt = event.value(QStringLiteral("timestamp")).toString(
            QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs));

    rebuildDerivedState();
    upsertRecentSession();
}

void VoyuClient::onStreamReadyRead() {
    if (!m_streamReply) {
        return;
    }

    m_streamBuffer.append(m_streamReply->readAll());
    QString normalized = QString::fromUtf8(m_streamBuffer);
    normalized.replace(QStringLiteral("\r\n"), QStringLiteral("\n"));
    m_streamBuffer = normalized.toUtf8();

    while (true) {
        const int separatorIndex = m_streamBuffer.indexOf("\n\n");
        if (separatorIndex < 0) {
            break;
        }

        const QByteArray chunk = m_streamBuffer.left(separatorIndex);
        m_streamBuffer.remove(0, separatorIndex + 2);
        processSseChunk(chunk);
    }
}

void VoyuClient::onStreamFinished() {
    QPointer<QNetworkReply> finishedReply = m_streamReply;
    if (!finishedReply) {
        return;
    }

    const QByteArray trailingBytes = finishedReply->readAll();
    if (!trailingBytes.isEmpty()) {
        m_streamBuffer.append(trailingBytes);
    }
    const QByteArray pendingChunk = m_streamBuffer;

    if (!m_streamBuffer.trimmed().isEmpty()) {
        processSseChunk(m_streamBuffer);
        m_streamBuffer.clear();
    }

    const QNetworkReply::NetworkError networkError = finishedReply->error();
    if (networkError == QNetworkReply::OperationCanceledError) {
        if (m_documentStatus != QStringLiteral("DRAFT")) {
            m_documentStatus = QStringLiteral("ABORTED");
        }
        if (m_documentErrorMessage.isEmpty()) {
            m_documentErrorMessage = QStringLiteral("流式任务已被手动中止。");
        }
        setStreamStatus(QStringLiteral("已中止"));
    } else if (networkError != QNetworkReply::NoError) {
        m_documentStatus = QStringLiteral("FAILED");
        m_documentErrorMessage = formatReplyError(finishedReply, pendingChunk);
        if (m_documentErrorMessage.isEmpty()) {
            m_documentErrorMessage = finishedReply->errorString();
        }
        const int httpStatus = finishedReply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        setStreamStatus(httpStatus > 0
                                ? QStringLiteral("请求失败 · HTTP %1").arg(httpStatus)
                                : QStringLiteral("请求失败"));
    } else {
        if (m_documentStatus.isEmpty() || m_documentStatus == QStringLiteral("RUNNING")) {
            m_documentStatus = m_finalAnswer.isEmpty() ? QStringLiteral("RUNNING") : QStringLiteral("COMPLETED");
        }
        setStreamStatus(m_documentStatus == QStringLiteral("COMPLETED")
                                ? QStringLiteral("已完成")
                                : QStringLiteral("执行结束"));
    }

    m_documentUpdatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    m_streamRuntimeTimer.stop();
    m_streamStartedAtMs = 0;
    m_streaming = false;
    emit streamingChanged();

    rebuildDerivedState();
    upsertRecentSession();
    refreshSessions();

    finishedReply->deleteLater();
    m_streamReply = nullptr;
}

void VoyuClient::processSseChunk(const QByteArray &chunk) {
    const QList<QByteArray> lines = chunk.split('\n');
    QByteArray data;

    for (const QByteArray &line : lines) {
        if (line.startsWith("data:")) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(line.mid(5).trimmed());
        }
    }

    if (data.isEmpty()) {
        return;
    }

    const QJsonDocument document = QJsonDocument::fromJson(data);
    if (!document.isObject()) {
        return;
    }

    ingestEvent(document.object());
}

void VoyuClient::loadRecentSessions() {
    QSettings settings;
    const QByteArray raw = settings.value(QStringLiteral("recentSessions")).toByteArray();
    if (raw.isEmpty()) {
        m_recentSessions.clear();
        return;
    }

    const QJsonDocument document = QJsonDocument::fromJson(raw);
    if (!document.isArray()) {
        m_recentSessions.clear();
        return;
    }

    m_recentSessions = document.array().toVariantList();
}

void VoyuClient::saveRecentSessions() const {
    QSettings settings;
    settings.setValue(QStringLiteral("recentSessions"),
                      QJsonDocument(QJsonArray::fromVariantList(m_recentSessions)).toJson(QJsonDocument::Compact));
}

void VoyuClient::upsertRecentSession() {
    if (m_currentSessionId.isEmpty()) {
        return;
    }

    QVariantMap existingSummary;
    for (const QVariant &item : m_recentSessions) {
        const QVariantMap map = item.toMap();
        if (map.value(QStringLiteral("sessionId")).toString() == m_currentSessionId) {
            existingSummary = map;
            break;
        }
    }

    const QString stableTitle = existingSummary.value(QStringLiteral("title")).toString().trimmed().isEmpty()
            ? clip(m_requestSnapshot.value(QStringLiteral("message")).toString(), 48)
            : existingSummary.value(QStringLiteral("title")).toString().trimmed();
    QVariantList nextSessions;
    QVariantMap summary{
            {QStringLiteral("sessionId"), m_currentSessionId},
            {QStringLiteral("title"), stableTitle},
            {QStringLiteral("preview"), clip(!m_finalAnswer.trimmed().isEmpty()
                                                     ? m_finalAnswer
                                                     : m_requestSnapshot.value(QStringLiteral("message")).toString(), 92)},
            {QStringLiteral("destination"), !m_requestSnapshot.value(QStringLiteral("destination")).toString().trimmed().isEmpty()
                                                     ? m_requestSnapshot.value(QStringLiteral("destination")).toString()
                                                     : existingSummary.value(QStringLiteral("destination")).toString()},
            {QStringLiteral("travelDays"), !m_requestSnapshot.value(QStringLiteral("travelDays")).toString().trimmed().isEmpty()
                                                    ? m_requestSnapshot.value(QStringLiteral("travelDays")).toString()
                                                    : existingSummary.value(QStringLiteral("travelDays")).toString()},
            {QStringLiteral("status"), sessionStatus()},
            {QStringLiteral("updatedAt"), m_updatedAtText},
            {QStringLiteral("messageCount"), m_conversationItems.size()}
    };

    nextSessions.append(summary);
    for (const QVariant &item : m_recentSessions) {
        const QVariantMap map = item.toMap();
        if (map.value(QStringLiteral("sessionId")).toString() == m_currentSessionId) {
            continue;
        }
        nextSessions.append(map);
        if (nextSessions.size() >= 20) {
            break;
        }
    }

    m_recentSessions = nextSessions;
    saveRecentSessions();
    emit recentSessionsChanged();
}

void VoyuClient::removeRecentSession(const QString &sessionId) {
    QVariantList nextSessions;
    for (const QVariant &item : m_recentSessions) {
        const QVariantMap map = item.toMap();
        if (map.value(QStringLiteral("sessionId")).toString() == sessionId) {
            continue;
        }
        nextSessions.append(map);
    }
    m_recentSessions = nextSessions;
}

QString VoyuClient::clip(const QString &value, int maxLength) {
    const QString normalized = value.trimmed();
    if (normalized.size() <= maxLength) {
        return normalized;
    }
    return normalized.left(maxLength - 1) + QStringLiteral("…");
}

QString VoyuClient::statusLabel(const QString &statusCode) {
    if (statusCode == QStringLiteral("RUNNING")) {
        return QStringLiteral("执行中");
    }
    if (statusCode == QStringLiteral("COMPLETED")) {
        return QStringLiteral("已完成");
    }
    if (statusCode == QStringLiteral("DONE")) {
        return QStringLiteral("成功");
    }
    if (statusCode == QStringLiteral("FAILED")) {
        return QStringLiteral("失败");
    }
    if (statusCode == QStringLiteral("ABORTED")) {
        return QStringLiteral("已中止");
    }
    if (statusCode == QStringLiteral("PENDING")) {
        return QStringLiteral("待执行");
    }
    if (statusCode == QStringLiteral("RETRYING")) {
        return QStringLiteral("重试中");
    }
    if (statusCode == QStringLiteral("SKIPPED")) {
        return QStringLiteral("已跳过");
    }
    if (statusCode == QStringLiteral("SUCCESS")) {
        return QStringLiteral("成功");
    }
    if (statusCode == QStringLiteral("DRAFT")) {
        return QStringLiteral("未开始");
    }
    return QStringLiteral("未开始");
}

QString VoyuClient::eventLabel(const QString &eventType) {
    if (eventType == QStringLiteral("MEMORY")) {
        return QStringLiteral("会话记忆");
    }
    if (eventType == QStringLiteral("THOUGHT")) {
        return QStringLiteral("主循环思考");
    }
    if (eventType == QStringLiteral("PLAN_DRAFT")) {
        return QStringLiteral("规划器草案");
    }
    if (eventType == QStringLiteral("TASK_BOOK")) {
        return QStringLiteral("任务书");
    }
    if (eventType == QStringLiteral("TASK_STATUS")) {
        return QStringLiteral("任务状态");
    }
    if (eventType == QStringLiteral("TOOL_CALL")) {
        return QStringLiteral("工具调用");
    }
    if (eventType == QStringLiteral("TOOL_RESULT")) {
        return QStringLiteral("工具结果");
    }
    if (eventType == QStringLiteral("WARNING")) {
        return QStringLiteral("异常告警");
    }
    if (eventType == QStringLiteral("FINAL_ANSWER")) {
        return QStringLiteral("最终方案");
    }
    return eventType;
}

QString VoyuClient::formatTimestamp(const QString &value) {
    if (value.trimmed().isEmpty()) {
        return QStringLiteral("-");
    }
    const QDateTime parsed = QDateTime::fromString(value, Qt::ISODate);
    if (!parsed.isValid()) {
        return value;
    }
    return parsed.toLocalTime().toString(QStringLiteral("yyyy-MM-dd HH:mm:ss"));
}

QString VoyuClient::formatElapsedDuration(qint64 elapsedMs) {
    const qint64 totalSeconds = qMax<qint64>(0, elapsedMs / 1000);
    if (totalSeconds < 60) {
        return QStringLiteral("已工作 %1 秒").arg(totalSeconds);
    }

    const qint64 minutes = totalSeconds / 60;
    const qint64 seconds = totalSeconds % 60;
    return QStringLiteral("已工作 %1 分 %2 秒").arg(minutes).arg(seconds);
}

QString VoyuClient::sourceLabel(const QString &phase) {
    if (phase == QStringLiteral("PLAN")) {
        return QStringLiteral("Plan");
    }
    if (phase == QStringLiteral("EXECUTE")) {
        return QStringLiteral("Execute");
    }
    return QStringLiteral("System");
}

QString VoyuClient::modeLabel(const QString &mode) {
    if (mode == QStringLiteral("PARALLEL")) {
        return QStringLiteral("并行");
    }
    if (mode == QStringLiteral("SERIAL")) {
        return QStringLiteral("串行");
    }
    return QStringLiteral("-");
}

QString VoyuClient::summarizeValue(const QJsonValue &value) {
    if (value.isString()) {
        return clip(value.toString(), 160);
    }
    if (value.isDouble()) {
        return QString::number(value.toDouble());
    }
    if (value.isBool()) {
        return value.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    }
    if (value.isArray()) {
        return QStringLiteral("数组(%1)").arg(value.toArray().size());
    }
    if (value.isObject()) {
        const QJsonObject object = value.toObject();
        if (object.contains(QStringLiteral("message"))) {
            return clip(object.value(QStringLiteral("message")).toString(), 160);
        }
        if (object.contains(QStringLiteral("answer"))) {
            return clip(object.value(QStringLiteral("answer")).toString(), 160);
        }
        return clip(QString::fromUtf8(QJsonDocument(object).toJson(QJsonDocument::Compact)), 160);
    }
    return QStringLiteral("-");
}

QString VoyuClient::summarizeEvent(const QString &eventType, const QJsonObject &payload) {
    if (eventType == QStringLiteral("MEMORY")) {
        return payload.value(QStringLiteral("summary")).toString(QStringLiteral("历史记忆已加载"));
    }
    if (eventType == QStringLiteral("THOUGHT")) {
        return payload.value(QStringLiteral("message")).toString();
    }
    if (eventType == QStringLiteral("PLAN_DRAFT")) {
        return payload.value(QStringLiteral("thought")).toString();
    }
    if (eventType == QStringLiteral("TASK_BOOK")) {
        const int taskCount = payload.value(QStringLiteral("tasks")).toArray().size();
        const QString script = payload.value(QStringLiteral("taskScript")).toString().replace('\n', QStringLiteral(" | "));
        return QStringLiteral("%1 · %2 个任务 · %3")
                .arg(payload.value(QStringLiteral("mission")).toString(QStringLiteral("已生成任务书")))
                .arg(taskCount)
                .arg(script.isEmpty() ? QStringLiteral("无脚本") : clip(script, 160));
    }
    if (eventType == QStringLiteral("TASK_STATUS")) {
        const QString phase = payload.value(QStringLiteral("phase")).toString();
        const int round = payload.value(QStringLiteral("round")).toInt();
        const int step = payload.value(QStringLiteral("step")).toInt();
        const int attempt = payload.value(QStringLiteral("attempt")).toInt();
        const QString sequenceText = phase == QStringLiteral("PLAN")
                ? (step > 0 ? QStringLiteral(" / Step %1").arg(step) : QString())
                : (attempt > 0 ? QStringLiteral(" / 第%1次").arg(attempt) : QString());
        return QStringLiteral("[%1] %2 -> %3（第%4轮%5）")
                .arg(sourceLabel(phase))
                .arg(payload.value(QStringLiteral("taskName")).toString(payload.value(QStringLiteral("taskId")).toString()))
                .arg(statusLabel(payload.value(QStringLiteral("status")).toString(QStringLiteral("UNKNOWN"))))
                .arg(round)
                .arg(sequenceText);
    }
    if (eventType == QStringLiteral("TOOL_CALL")) {
        return QStringLiteral("%1 · %2")
                .arg(payload.value(QStringLiteral("toolName")).toString(QStringLiteral("未命名工具")))
                .arg(summarizeValue(payload.value(QStringLiteral("arguments"))));
    }
    if (eventType == QStringLiteral("TOOL_RESULT")) {
        return QStringLiteral("%1 · %2")
                .arg(payload.value(QStringLiteral("toolName")).toString(QStringLiteral("未命名工具")))
                .arg(summarizeValue(payload.value(QStringLiteral("result"))));
    }
    if (eventType == QStringLiteral("WARNING")) {
        return payload.value(QStringLiteral("message")).toString(QStringLiteral("出现异常"));
    }
    if (eventType == QStringLiteral("FINAL_ANSWER")) {
        return clip(payload.value(QStringLiteral("answer")).toString(), 180);
    }
    return summarizeValue(payload);
}

QString VoyuClient::formatTaskScript(const QJsonObject &taskBookPayload) {
    const QString rawScript = taskBookPayload.value(QStringLiteral("taskScript")).toString().trimmed();
    if (rawScript.isEmpty()) {
        return QString();
    }

    QMap<QString, QString> taskNames;
    for (const QJsonValue &value : taskBookPayload.value(QStringLiteral("tasks")).toArray()) {
        const QJsonObject task = value.toObject();
        const QString taskId = safeString(task, QStringLiteral("taskId"));
        if (!taskId.isEmpty()) {
            taskNames.insert(taskId, task.value(QStringLiteral("name")).toString(taskId));
        }
    }

    QStringList lines;
    int lineIndex = 1;
    for (const QString &line : rawScript.split('\n', Qt::SkipEmptyParts)) {
        QStringList parts;
        for (const QString &part : line.split(QStringLiteral("<sep>"), Qt::SkipEmptyParts)) {
            const QString taskId = part.trimmed();
            if (taskId.isEmpty()) {
                continue;
            }
            const QString title = taskNames.value(taskId, taskId);
            parts.append(QStringLiteral("%1 %2").arg(taskId, title));
        }
        if (!parts.isEmpty()) {
            lines.append(QStringLiteral("%1. %2").arg(lineIndex++).arg(parts.join(QStringLiteral(" <sep> "))));
        }
    }

    return lines.join('\n');
}

double VoyuClient::nextLocalSequence() const {
    double maxSequence = 0;
    const QJsonArray persistedMessages = m_messages.isEmpty() ? legacyTurnsToMessages(m_turns) : m_messages;
    for (const QJsonValue &value : persistedMessages) {
        maxSequence = qMax(maxSequence, value.toObject().value(QStringLiteral("sequence")).toDouble(0));
    }
    return maxSequence + 1;
}

QJsonArray VoyuClient::sortEvents(const QJsonArray &events) {
    QList<QJsonObject> sorted;
    sorted.reserve(events.size());
    for (const QJsonValue &value : events) {
        sorted.append(value.toObject());
    }

    std::sort(sorted.begin(), sorted.end(), [](const QJsonObject &left, const QJsonObject &right) {
        const QString leftTime = left.value(QStringLiteral("timestamp")).toString();
        const QString rightTime = right.value(QStringLiteral("timestamp")).toString();
        if (leftTime == rightTime) {
            return left.value(QStringLiteral("round")).toInt() < right.value(QStringLiteral("round")).toInt();
        }
        return leftTime < rightTime;
    });

    QJsonArray result;
    for (const QJsonObject &event : sorted) {
        result.append(event);
    }
    return result;
}
