#pragma once

#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>

class VoyuClient : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString baseUrl READ baseUrl WRITE setBaseUrl NOTIFY baseUrlChanged)
    Q_PROPERTY(QString streamStatus READ streamStatus NOTIFY streamStatusChanged)
    Q_PROPERTY(bool streaming READ streaming NOTIFY streamingChanged)
    Q_PROPERTY(QString currentSessionId READ currentSessionId NOTIFY sessionChanged)
    Q_PROPERTY(QString sessionStatus READ sessionStatus NOTIFY sessionChanged)
    Q_PROPERTY(QString userBrief READ userBrief NOTIFY sessionChanged)
    Q_PROPERTY(QString planThought READ planThought NOTIFY sessionChanged)
    Q_PROPERTY(QString mission READ mission NOTIFY sessionChanged)
    Q_PROPERTY(QString finalAnswer READ finalAnswer NOTIFY sessionChanged)
    Q_PROPERTY(QString updatedAtText READ updatedAtText NOTIFY sessionChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList requestChips READ requestChips NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList conversationItems READ conversationItems NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList reasoningItems READ reasoningItems NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList taskItems READ taskItems NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList knowledgeHits READ knowledgeHits NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList timelineItems READ timelineItems NOTIFY sessionChanged)
    Q_PROPERTY(QVariantList infrastructureItems READ infrastructureItems NOTIFY infrastructureChanged)
    Q_PROPERTY(QVariantList recentSessions READ recentSessions NOTIFY recentSessionsChanged)

public:
    explicit VoyuClient(QObject *parent = nullptr);

    QString baseUrl() const;
    void setBaseUrl(const QString &baseUrl);

    QString streamStatus() const;
    bool streaming() const;
    QString currentSessionId() const;
    QString activeSessionId() const;
    QString sessionStatus() const;
    QString userBrief() const;
    QString planThought() const;
    QString mission() const;
    QString finalAnswer() const;
    QString updatedAtText() const;
    QString errorMessage() const;
    QVariantMap requestSnapshot() const;
    QVariantList requestChips() const;
    QVariantList conversationItems() const;
    QVariantList reasoningItems() const;
    QVariantList taskItems() const;
    QVariantList knowledgeHits() const;
    QVariantList timelineItems() const;
    QVariantList infrastructureItems() const;
    QVariantList recentSessions() const;

    Q_INVOKABLE void startPlan(const QVariantMap &payload);
    Q_INVOKABLE void loadSession(const QString &sessionId);
    Q_INVOKABLE void refreshInfrastructure();
    Q_INVOKABLE void refreshSessions();
    Q_INVOKABLE void deleteSession(const QString &sessionId);
    Q_INVOKABLE void startNewSession();
    Q_INVOKABLE void abortStream();

signals:
    void baseUrlChanged();
    void streamStatusChanged();
    void streamingChanged();
    void sessionChanged();
    void infrastructureChanged();
    void recentSessionsChanged();

private:
    QUrl endpoint(const QString &path) const;
    void setStreamStatus(const QString &statusText);
    void resetSession(const QJsonObject &requestSnapshot);
    void resetDraftState();
    void appendLocalMessage(const QString &role,
                            const QString &content,
                            const QString &messageType,
                            const QJsonObject &requestSnapshot = QJsonObject());
    void hydrateFromDocument(const QJsonObject &document);
    void rebuildDerivedState();
    void ingestEvent(const QJsonObject &event);
    void onStreamReadyRead();
    void onStreamFinished();
    void processSseChunk(const QByteArray &chunk);
    void loadRecentSessions();
    void saveRecentSessions() const;
    void upsertRecentSession();
    void removeRecentSession(const QString &sessionId);

    static QString clip(const QString &value, int maxLength);
    static QString statusLabel(const QString &statusCode);
    static QString eventLabel(const QString &eventType);
    static QString formatTimestamp(const QString &value);
    static QString formatElapsedDuration(qint64 elapsedMs);
    static QString sourceLabel(const QString &phase);
    static QString modeLabel(const QString &mode);
    static QString summarizeValue(const QJsonValue &value);
    static QString summarizeEvent(const QString &eventType, const QJsonObject &payload);
    static QString formatTaskScript(const QJsonObject &taskBookPayload);
    static QJsonArray sortEvents(const QJsonArray &events);
    double nextLocalSequence() const;

    QNetworkAccessManager m_networkManager;
    QPointer<QNetworkReply> m_streamReply;
    QByteArray m_streamBuffer;

    QString m_baseUrl = QStringLiteral("http://175.178.47.238");
    QString m_streamStatus = QStringLiteral("空闲");
    bool m_streaming = false;
    qint64 m_streamStartedAtMs = 0;
    QTimer m_streamRuntimeTimer;

    QString m_currentSessionId;
    QString m_sessionStatusCode = QStringLiteral("IDLE");
    QString m_userBrief = QStringLiteral("还没有开始新的规划，请先输入你的旅行目标。");
    QString m_planThought = QStringLiteral("等待规划器给出任务拆解。");
    QString m_mission = QStringLiteral("任务书生成后，这里会展示 mission。");
    QString m_finalAnswer;
    QString m_updatedAtText = QStringLiteral("-");
    QString m_errorMessage;

    QJsonObject m_requestSnapshot;
    QJsonArray m_events;
    QJsonArray m_messages;
    QJsonArray m_turns;

    QString m_documentStatus;
    QString m_documentFinalAnswer;
    QString m_documentErrorMessage;
    QString m_documentUpdatedAt;
    QString m_documentCompletedAt;

    QVariantList m_requestChips;
    QVariantList m_conversationItems;
    QVariantList m_reasoningItems;
    QVariantList m_taskItems;
    QVariantList m_knowledgeHits;
    QVariantList m_timelineItems;
    QVariantList m_infrastructureItems;
    QVariantList m_recentSessions;
};
