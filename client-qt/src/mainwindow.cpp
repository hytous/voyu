#include "mainwindow.h"

#include "ui_mainwindow.h"
#include "voyuclient.h"

#include <QAbstractItemView>
#include <QDate>
#include <QDateTime>
#include <QFontMetrics>
#include <QFrame>
#include <QHeaderView>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QListWidgetItem>
#include <QSignalBlocker>
#include <QSizePolicy>
#include <QSpacerItem>
#include <QTextBrowser>
#include <QTextDocument>
#include <QTextEdit>
#include <QVBoxLayout>

namespace {
QString kindBadgeText(const QString &kind) {
    if (kind == QStringLiteral("QUESTION")) {
        return QStringLiteral("追问");
    }
    if (kind == QStringLiteral("STATUS")) {
        return QStringLiteral("思考中");
    }
    if (kind == QStringLiteral("RETRYING")) {
        return QStringLiteral("重试中");
    }
    if (kind == QStringLiteral("ERROR")) {
        return QStringLiteral("错误");
    }
    if (kind == QStringLiteral("PLAN")) {
        return QStringLiteral("方案");
    }
    return QString();
}

QString compactText(const QString &raw) {
    QString text = raw;
    text.replace(QStringLiteral("\r"), QStringLiteral(" "));
    text.replace(QStringLiteral("\n"), QStringLiteral(" "));
    text = text.simplified();
    while (!text.isEmpty()) {
        const QChar first = text.front();
        if (first == QChar('#') || first == QChar('-') || first == QChar('*') ||
            first == QChar('>') || first == QChar(0x2022) || first.isSpace()) {
            text.remove(0, 1);
            text = text.trimmed();
            continue;
        }
        break;
    }
    return text.trimmed();
}

QString clipCompactText(const QString &raw, int maxLength) {
    const QString normalized = compactText(raw);
    if (normalized.size() <= maxLength) {
        return normalized;
    }
    return normalized.left(maxLength - 3).trimmed() + QStringLiteral("...");
}

int preferredBubbleWidth(const QFontMetrics &metrics, const QString &text) {
    const QStringList lines = text.split('\n');
    int longestWidth = 0;
    for (const QString &line : lines) {
        const QString compactLine = compactText(line);
        if (compactLine.isEmpty()) {
            continue;
        }
        longestWidth = qMax(longestWidth, metrics.horizontalAdvance(compactLine.left(28)));
    }

    if (longestWidth <= 0) {
        longestWidth = metrics.horizontalAdvance(QStringLiteral("思考中，正在处理你的请求"));
    }

    return qBound(180, longestWidth + 92, 720);
}

QString sessionBucket(const QString &updatedAtText) {
    const QDateTime updatedAt = QDateTime::fromString(updatedAtText, QStringLiteral("yyyy-MM-dd HH:mm:ss"));
    if (!updatedAt.isValid()) {
        return QStringLiteral("更早");
    }

    const QDate today = QDate::currentDate();
    const int dayDelta = updatedAt.date().daysTo(today);
    if (dayDelta <= 0) {
        return QStringLiteral("今天");
    }
    if (dayDelta == 1) {
        return QStringLiteral("昨天");
    }
    if (dayDelta <= 7) {
        return QStringLiteral("最近 7 天");
    }
    return QStringLiteral("更早");
}

bool matchesSessionFilter(const QVariantMap &itemData, const QString &filter) {
    const QString normalized = filter.trimmed().toLower();
    if (normalized.isEmpty()) {
        return true;
    }

    const QString haystack = QStringList{
            itemData.value(QStringLiteral("title")).toString(),
            itemData.value(QStringLiteral("preview")).toString(),
            itemData.value(QStringLiteral("destination")).toString(),
            itemData.value(QStringLiteral("travelDays")).toString(),
            itemData.value(QStringLiteral("status")).toString(),
            itemData.value(QStringLiteral("updatedAt")).toString()
    }.join(QStringLiteral(" ")).toLower();

    return haystack.contains(normalized);
}

QWidget *buildSectionHeaderWidget(const QString &title, QWidget *parent = nullptr) {
    auto *container = new QWidget(parent);
    auto *layout = new QHBoxLayout(container);
    layout->setContentsMargins(6, 10, 6, 2);
    layout->setSpacing(0);

    auto *label = new QLabel(title, container);
    label->setObjectName(QStringLiteral("sessionSectionLabel"));
    layout->addWidget(label);
    layout->addStretch(1);
    return container;
}

QWidget *buildSessionCardWidget(const QVariantMap &itemData, bool active, QWidget *parent = nullptr) {
    auto *card = new QFrame(parent);
    card->setObjectName(QStringLiteral("sessionCard"));
    card->setProperty("active", active);
    card->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);

    auto *layout = new QVBoxLayout(card);
    layout->setContentsMargins(12, 10, 12, 10);
    layout->setSpacing(4);

    const QString fullTitle = compactText(itemData.value(QStringLiteral("title")).toString());
    const QString titleText = clipCompactText(fullTitle, 12);

    auto *titleLabel = new QLabel(titleText.isEmpty() ? QStringLiteral("未命名会话") : titleText, card);
    titleLabel->setObjectName(QStringLiteral("sessionTitleLabel"));
    titleLabel->setWordWrap(false);
    titleLabel->setToolTip(fullTitle);

    const QString destination = itemData.value(QStringLiteral("destination")).toString();
    const QString travelDays = itemData.value(QStringLiteral("travelDays")).toString();
    const QString status = itemData.value(QStringLiteral("status")).toString();
    QStringList metaParts;
    if (!destination.isEmpty()) {
        metaParts << destination;
    }
    if (!travelDays.isEmpty()) {
        metaParts << travelDays;
    }
    if (!status.isEmpty()) {
        metaParts << status;
    }

    auto *metaLabel = new QLabel(metaParts.join(QStringLiteral(" · ")), card);
    metaLabel->setObjectName(QStringLiteral("sessionMetaLabel"));
    metaLabel->setWordWrap(true);

    auto *timeLabel = new QLabel(itemData.value(QStringLiteral("updatedAt")).toString(), card);
    timeLabel->setObjectName(QStringLiteral("sessionTimeLabel"));

    layout->addWidget(titleLabel);
    if (!metaLabel->text().isEmpty()) {
        layout->addWidget(metaLabel);
    }
    layout->addWidget(timeLabel);
    return card;
}

QWidget *buildConversationBubbleWidget(const QVariantMap &itemData, QWidget *parent = nullptr) {
    const QString kind = itemData.value(QStringLiteral("kind")).toString();
    const bool isUser = kind == QStringLiteral("USER");
    const QString bodyText = itemData.value(QStringLiteral("body")).toString();
    const QString elapsedText = itemData.value(QStringLiteral("elapsed")).toString();

    auto *container = new QWidget(parent);
    auto *outerLayout = new QHBoxLayout(container);
    outerLayout->setContentsMargins(8, 6, 8, 6);
    outerLayout->setSpacing(10);

    auto *bubble = new QFrame(container);
    bubble->setObjectName(QStringLiteral("chatBubble"));
    bubble->setProperty("role", isUser ? QStringLiteral("user") : QStringLiteral("assistant"));
    bubble->setProperty("kind", kind);
    bubble->setSizePolicy(isUser ? QSizePolicy::Expanding : QSizePolicy::Preferred, QSizePolicy::Preferred);

    auto *bubbleLayout = new QVBoxLayout(bubble);
    bubbleLayout->setContentsMargins(14, 12, 14, 12);
    bubbleLayout->setSpacing(6);

    auto *headerLayout = new QHBoxLayout();
    headerLayout->setContentsMargins(0, 0, 0, 0);
    headerLayout->setSpacing(8);

    auto *nameLabel = new QLabel(isUser ? QStringLiteral("你") : QStringLiteral("Voyu"), bubble);
    nameLabel->setObjectName(QStringLiteral("chatAuthorLabel"));
    nameLabel->setProperty("role", isUser ? QStringLiteral("user") : QStringLiteral("assistant"));

    auto *timeLabel = new QLabel(itemData.value(QStringLiteral("time")).toString(), bubble);
    timeLabel->setObjectName(QStringLiteral("chatTimeLabel"));
    timeLabel->setProperty("role", isUser ? QStringLiteral("user") : QStringLiteral("assistant"));

    headerLayout->addWidget(nameLabel, 0, Qt::AlignLeft);

    const QString badgeText = kindBadgeText(kind);
    if (!badgeText.isEmpty()) {
        auto *badgeLabel = new QLabel(badgeText, bubble);
        badgeLabel->setObjectName(QStringLiteral("chatKindBadge"));
        badgeLabel->setProperty("kind", kind);
        headerLayout->addWidget(badgeLabel, 0, Qt::AlignLeft);
    }

    headerLayout->addStretch(1);
    headerLayout->addWidget(timeLabel, 0, Qt::AlignRight);

    auto *bodyLabel = new QLabel(bodyText, bubble);
    bodyLabel->setObjectName(QStringLiteral("chatBodyLabel"));
    bodyLabel->setProperty("role", isUser ? QStringLiteral("user") : QStringLiteral("assistant"));
    bodyLabel->setTextInteractionFlags(Qt::TextSelectableByMouse | Qt::LinksAccessibleByMouse);
    bodyLabel->setWordWrap(true);
    bodyLabel->setOpenExternalLinks(true);
    bodyLabel->setMinimumHeight(bodyLabel->fontMetrics().lineSpacing() + 10);

    if (isUser) {
        bubble->setMaximumWidth(QWIDGETSIZE_MAX);
        bodyLabel->setMaximumWidth(QWIDGETSIZE_MAX);
        bodyLabel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    } else {
        const int bubbleWidth = preferredBubbleWidth(bodyLabel->fontMetrics(), bodyText);
        bubble->setMaximumWidth(bubbleWidth);
        bodyLabel->setMaximumWidth(bubbleWidth - 28);
    }

    bubbleLayout->addLayout(headerLayout);
    bubbleLayout->addWidget(bodyLabel);
    if (!elapsedText.trimmed().isEmpty()) {
        auto *elapsedLabel = new QLabel(elapsedText, bubble);
        elapsedLabel->setObjectName(QStringLiteral("chatElapsedLabel"));
        elapsedLabel->setProperty("role", isUser ? QStringLiteral("user") : QStringLiteral("assistant"));
        bubbleLayout->addWidget(elapsedLabel, 0, isUser ? Qt::AlignRight : Qt::AlignLeft);
    }

    if (isUser) {
        outerLayout->addWidget(bubble, 1);
    } else {
        outerLayout->addWidget(bubble, 0, Qt::AlignLeft);
        outerLayout->addStretch(1);
    }
    bubble->layout()->activate();
    bubble->setMinimumHeight(qMax(bubble->sizeHint().height(), 74));
    container->layout()->activate();
    return container;
}
}

MainWindow::MainWindow(VoyuClient *client, QWidget *parent)
    : QMainWindow(parent), ui(new Ui::MainWindow), m_client(client) {
    ui->setupUi(this);
    setupUiState();

    ui->baseUrlEdit->setText(m_client->baseUrl());

    connect(ui->baseUrlEdit, &QLineEdit::editingFinished, this, &MainWindow::refreshInfrastructure);
    connect(ui->newJourneyButton, &QPushButton::clicked, this, &MainWindow::clearForm);
    connect(ui->startPlanButton, &QPushButton::clicked, this, &MainWindow::submitPlan);
    connect(ui->abortButton, &QPushButton::clicked, this, &MainWindow::abortPlan);
    connect(ui->recentSessionsList, &QListWidget::itemClicked, this, &MainWindow::loadRecentSession);
    connect(ui->recentSessionsList, &QListWidget::itemDoubleClicked, this, &MainWindow::loadRecentSession);
    connect(ui->loadSessionButton, &QPushButton::clicked, this, &MainWindow::loadSelectedSession);
    connect(ui->deleteSessionButton, &QPushButton::clicked, this, &MainWindow::deleteSelectedSession);
    connect(ui->recentSessionsList, &QListWidget::itemSelectionChanged, this, &MainWindow::updateSessionButtons);

    connect(m_client, &VoyuClient::baseUrlChanged, this, [this]() {
        if (ui->baseUrlEdit->text() != m_client->baseUrl()) {
            ui->baseUrlEdit->setText(m_client->baseUrl());
        }
    });
    connect(m_client, &VoyuClient::streamingChanged, this, &MainWindow::refreshFromClient);
    connect(m_client, &VoyuClient::sessionChanged, this, &MainWindow::refreshFromClient);
    connect(m_client, &VoyuClient::infrastructureChanged, this, &MainWindow::refreshFromClient);
    connect(m_client, &VoyuClient::recentSessionsChanged, this, &MainWindow::refreshFromClient);

    refreshFromClient();
    m_client->refreshInfrastructure();
    m_client->refreshSessions();
}

MainWindow::~MainWindow() {
    delete ui;
}

void MainWindow::resizeEvent(QResizeEvent *event) {
    QMainWindow::resizeEvent(event);
    populateRecentSessions();
    populateConversationList();
}

void MainWindow::refreshFromClient() {
    ui->abortButton->setEnabled(m_client->streaming());

    populateRecentSessions();
    populateInfrastructure();
    populateSession();
    updateSessionButtons();
}

void MainWindow::refreshInfrastructure() {
    const QString normalizedBaseUrl = ui->baseUrlEdit->text().trimmed();
    if (normalizedBaseUrl.isEmpty()) {
        ui->baseUrlEdit->setText(m_client->baseUrl());
        return;
    }

    if (normalizedBaseUrl == m_client->baseUrl()) {
        return;
    }

    m_client->setBaseUrl(normalizedBaseUrl);
    m_client->refreshInfrastructure();
    m_client->refreshSessions();
}

void MainWindow::submitPlan() {
    m_client->setBaseUrl(ui->baseUrlEdit->text());
    m_client->startPlan(collectPayload());
    ui->messageEdit->clear();
}

void MainWindow::abortPlan() {
    m_client->abortStream();
}

void MainWindow::clearForm() {
    m_selectedSessionId.clear();
    m_client->startNewSession();
    ui->messageEdit->clear();
    ui->departureEdit->clear();
    ui->messageEdit->setFocus();
}

void MainWindow::loadRecentSession(QListWidgetItem *item) {
    if (!item) {
        return;
    }
    const QString sessionId = item->data(Qt::UserRole).toString();
    if (sessionId.isEmpty()) {
        return;
    }
    m_selectedSessionId = sessionId;
    updateSessionButtons();
    m_client->setBaseUrl(ui->baseUrlEdit->text());
    m_client->loadSession(sessionId);
}

void MainWindow::loadSelectedSession() {
    const QString sessionId = selectedSessionId();
    if (sessionId.isEmpty()) {
        return;
    }
    m_selectedSessionId = sessionId;
    m_client->setBaseUrl(ui->baseUrlEdit->text());
    m_client->loadSession(sessionId);
}

void MainWindow::deleteSelectedSession() {
    const QString sessionId = selectedSessionId();
    if (sessionId.isEmpty()) {
        return;
    }
    m_selectedSessionId.clear();
    m_client->setBaseUrl(ui->baseUrlEdit->text());
    m_client->deleteSession(sessionId);
}

void MainWindow::updateSessionButtons() {
    const QString currentSelection = selectedSessionId();
    if (!currentSelection.isEmpty()) {
        m_selectedSessionId = currentSelection;
    }
    const bool hasSelection = !selectedSessionId().isEmpty();
    ui->loadSessionButton->setEnabled(hasSelection);
    ui->deleteSessionButton->setEnabled(hasSelection);
}

void MainWindow::setupUiState() {
    setMinimumSize(1360, 900);

    // 搜索框直接定义在 .ui 中，这里只连接信号
    connect(ui->sessionSearchEdit, &QLineEdit::textChanged, this, [this](const QString &text) {
        m_sessionFilter = text;
        populateRecentSessions();
        updateSessionButtons();
    });

    // 分割比例
    ui->mainSplitter->setStretchFactor(0, 0);
    ui->mainSplitter->setStretchFactor(1, 1);
    ui->mainSplitter->setStretchFactor(2, 0);
    ui->mainSplitter->setSizes({320, 920, 420});

    // 中间列：聊天区拉伸填充，输入框固定高度
    ui->centerLayout->setStretch(0, 4);
    ui->centerLayout->setStretch(1, 0);
    ui->conversationGroup->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Expanding);
    ui->conversationGroup->setMinimumHeight(520);
    ui->conversationList->setMinimumHeight(430);
    ui->payloadGroup->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    ui->payloadGroup->setMaximumHeight(260);

    ui->recentSessionsList->setUniformItemSizes(false);
    ui->recentSessionsList->setSpacing(6);
    ui->recentSessionsList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    ui->recentSessionsList->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    ui->conversationList->setWordWrap(true);
    ui->conversationList->setSpacing(10);
    ui->conversationList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    ui->conversationList->setSelectionMode(QAbstractItemView::NoSelection);
    ui->conversationList->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    ui->conversationList->setFocusPolicy(Qt::NoFocus);
    ui->reasoningList->setWordWrap(true);
    ui->knowledgeList->setWordWrap(true);
    ui->timelineList->setWordWrap(true);
    ui->reasoningList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    ui->knowledgeList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    ui->timelineList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);

    // userBriefBrowser / planThoughtBrowser / missionBrowser / finalAnswerBrowser
    // 均已从 .ui 中移除，不再需要初始化
    ui->messageEdit->setLineWrapMode(QTextEdit::WidgetWidth);
    ui->messageEdit->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    ui->messageEdit->document()->setDocumentMargin(10);

    ui->infraTree->setColumnCount(3);
    ui->infraTree->setHeaderLabels({QStringLiteral("组件"), QStringLiteral("状态"), QStringLiteral("说明")});
    ui->infraTree->header()->setStretchLastSection(true);

    ui->taskTree->setColumnCount(7);
    ui->taskTree->setHeaderLabels({
            QStringLiteral("工具任务"),
            QStringLiteral("来源"),
            QStringLiteral("轮次/尝试"),
            QStringLiteral("批次"),
            QStringLiteral("并发"),
            QStringLiteral("状态"),
            QStringLiteral("说明")
    });
    ui->taskTree->setWordWrap(true);
    ui->taskTree->header()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(2, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(3, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(4, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(5, QHeaderView::ResizeToContents);
    ui->taskTree->header()->setSectionResizeMode(6, QHeaderView::Stretch);

    QFont titleFont = font();
    titleFont.setBold(true);
    titleFont.setPointSizeF(titleFont.pointSizeF() * 1.45);
    ui->titleLabel->setFont(titleFont);

    QFont brandFont = titleFont;
    brandFont.setPointSizeF(brandFont.pointSizeF() * 1.05);
    ui->brandTitleLabel->setFont(brandFont);

    QFont eyebrowFont = font();
    eyebrowFont.setPointSizeF(qMax(eyebrowFont.pointSizeF() * 0.9, 9.0));
    ui->eyebrowLabel->setFont(eyebrowFont);
    ui->brandEyebrowLabel->setFont(eyebrowFont);

    ui->headerFrame->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    ui->headerLayout->setSpacing(8);
    ui->titleLayout->setSpacing(0);
    ui->baseUrlEdit->setSizePolicy(QSizePolicy::MinimumExpanding, QSizePolicy::Fixed);
    ui->conversationGroup->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Expanding);
    ui->conversationGroup->setMinimumHeight(520);
    ui->conversationList->setMinimumHeight(430);
    ui->payloadGroup->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Fixed);
    ui->payloadGroup->setMaximumHeight(260);
    ui->centerScrollArea->setFrameShape(QFrame::NoFrame);
    ui->rightScrollArea->setFrameShape(QFrame::NoFrame);

    setStyleSheet(QStringLiteral(R"(
QMainWindow, QWidget#centralWidget {
    background: #efe7d7;
    color: #231f18;
}
QFrame#headerFrame {
    background: #fff8ef;
    border: 1px solid #d7ccb8;
    border-radius: 12px;
}
QWidget#leftPanel {
    background: #241c16;
    color: #fff7ee;
    border-radius: 14px;
}
QWidget#leftPanel QLabel {
    color: #fff7ee;
}
QWidget#leftPanel QGroupBox {
    background: #30261f;
    color: #fff7ee;
    border: 1px solid #4c3c2f;
    border-radius: 12px;
    margin-top: 10px;
}
QWidget#leftPanel QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 4px;
}
QGroupBox {
    background: #fff8ef;
    border: 1px solid #d7ccb8;
    border-radius: 12px;
    margin-top: 10px;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 4px;
}
QLineEdit, QTextEdit, QTextBrowser, QListWidget, QTreeWidget {
    background: #fcfaf5;
    border: 1px solid #d7ccb8;
    border-radius: 8px;
    padding: 6px 8px 10px 8px;
}
QPushButton {
    background: #0f766e;
    color: white;
    border: none;
    border-radius: 8px;
    padding: 6px 12px;
}
QPushButton:hover {
    background: #0b5b55;
}
QPushButton:disabled {
    background: #9ca3af;
}
QFrame#brandFrame {
    background: #342920;
    border: 1px solid #4c3c2f;
    border-radius: 12px;
}
QListWidget::item, QTreeWidget::item {
    padding: 8px 6px;
}
QListWidget#recentSessionsList, QListWidget#conversationList {
    background: transparent;
    border: none;
    padding: 0;
}
QLineEdit#sessionSearchEdit {
    background: #fcfaf5;
    border: 1px solid #6a5647;
    border-radius: 10px;
    padding: 6px 10px;
    color: #231f18;
}
QFrame#sessionCard {
    background: #3a2d24;
    border: 1px solid #5a473a;
    border-radius: 12px;
}
QFrame#sessionCard[active="true"] {
    background: #0f766e;
    border: 1px solid #65c0b5;
}
QLabel#sessionTitleLabel {
    color: #fffaf3;
    font-weight: 700;
}
QLabel#sessionMetaLabel {
    color: #e6d9ca;
}
QLabel#sessionTimeLabel {
    color: #cbb9a6;
    font-size: 11px;
}
QLabel#sessionSectionLabel {
    color: #d9c7b3;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
}
QFrame#chatBubble {
    border-radius: 16px;
    border: 1px solid #d7ccb8;
}
QFrame#chatBubble[role="assistant"] {
    background: #fffdf9;
    border-color: #dbcdb8;
}
QFrame#chatBubble[role="user"] {
    background: #0f766e;
    border-color: #0b5b55;
}
QLabel#chatAuthorLabel {
    font-weight: 700;
}
QLabel#chatAuthorLabel[role="assistant"] {
    color: #2f241b;
}
QLabel#chatAuthorLabel[role="user"] {
    color: #effcf8;
}
QLabel#chatTimeLabel[role="assistant"] {
    color: #7a7064;
    font-size: 11px;
}
QLabel#chatTimeLabel[role="user"] {
    color: #d8f1ec;
    font-size: 11px;
}
QLabel#chatBodyLabel {
    font-size: 13px;
}
QLabel#chatBodyLabel[role="assistant"] {
    color: #251d16;
}
QLabel#chatBodyLabel[role="user"] {
    color: #f7fffd;
}
QLabel#chatElapsedLabel[role="assistant"] {
    color: #7a7064;
    font-size: 11px;
}
QLabel#chatElapsedLabel[role="user"] {
    color: #d8f1ec;
    font-size: 11px;
}
QLabel#chatKindBadge {
    border-radius: 9px;
    padding: 1px 8px;
    font-size: 11px;
    font-weight: 700;
}
QLabel#chatKindBadge[kind="QUESTION"] {
    background: #fff2c7;
    color: #8a5b00;
}
QLabel#chatKindBadge[kind="PLAN"] {
    background: #dff4ee;
    color: #0c625a;
}
QLabel#chatKindBadge[kind="ERROR"] {
    background: #fde7e7;
    color: #a33030;
}
)"));
}

void MainWindow::populateRecentSessions() {
    const QString currentSelection = selectedSessionId();
    const QString activeSessionId = m_client->activeSessionId();

    ui->recentSessionsList->clear();
    QString previousBucket;
    QListWidgetItem *selectedItem = nullptr;
    bool hasMatches = false;

    for (const QVariant &value : m_client->recentSessions()) {
        const QVariantMap itemData = value.toMap();
        const QString sessionId = itemData.value(QStringLiteral("sessionId")).toString();
        if (!matchesSessionFilter(itemData, m_sessionFilter)) {
            continue;
        }

        hasMatches = true;
        const QString bucket = sessionBucket(itemData.value(QStringLiteral("updatedAt")).toString());
        if (bucket != previousBucket) {
            previousBucket = bucket;
            auto *headerItem = new QListWidgetItem(ui->recentSessionsList);
            headerItem->setFlags(Qt::NoItemFlags);
            headerItem->setText(QString());
            headerItem->setData(Qt::DisplayRole, QString());
            headerItem->setForeground(Qt::transparent);
            QWidget *headerWidget = buildSectionHeaderWidget(bucket, ui->recentSessionsList);
            const int availableWidth = qMax(220, ui->recentSessionsList->viewport()->width() - 12);
            headerWidget->setFixedWidth(availableWidth);
            headerWidget->adjustSize();
            headerItem->setSizeHint(QSize(availableWidth, headerWidget->sizeHint().height()));
            ui->recentSessionsList->setItemWidget(headerItem, headerWidget);
        }

        auto *item = new QListWidgetItem(ui->recentSessionsList);
        item->setData(Qt::UserRole, sessionId);
        item->setText(QString());
        item->setData(Qt::DisplayRole, QString());
        item->setForeground(Qt::transparent);
        item->setFlags(Qt::ItemIsEnabled | Qt::ItemIsSelectable);

        const bool isActive = (!currentSelection.isEmpty() && sessionId == currentSelection) ||
                              (!activeSessionId.isEmpty() && sessionId == activeSessionId);
        QWidget *cardWidget = buildSessionCardWidget(itemData, isActive, ui->recentSessionsList);
        const int availableWidth = qMax(220, ui->recentSessionsList->viewport()->width() - 12);
        cardWidget->setFixedWidth(availableWidth);
        cardWidget->setToolTip(QStringLiteral("%1\n%2")
                                       .arg(itemData.value(QStringLiteral("title")).toString(),
                                            itemData.value(QStringLiteral("preview")).toString()));
        cardWidget->adjustSize();
        item->setSizeHint(QSize(availableWidth, cardWidget->sizeHint().height()));
        ui->recentSessionsList->setItemWidget(item, cardWidget);

        if (isActive && selectedItem == nullptr) {
            selectedItem = item;
        }
    }

    if (!hasMatches) {
        auto *emptyItem = new QListWidgetItem(ui->recentSessionsList);
        emptyItem->setFlags(Qt::NoItemFlags);
        emptyItem->setText(m_sessionFilter.trimmed().isEmpty()
                                   ? QStringLiteral("还没有历史会话。")
                                   : QStringLiteral("没有匹配的会话。"));
        return;
    }

    if (selectedItem != nullptr) {
        ui->recentSessionsList->setCurrentItem(selectedItem);
        m_selectedSessionId = selectedItem->data(Qt::UserRole).toString();
    }
}

void MainWindow::populateInfrastructure() {
    ui->infraTree->clear();
    for (const QVariant &value : m_client->infrastructureItems()) {
        const QVariantMap itemData = value.toMap();
        auto *item = new QTreeWidgetItem(ui->infraTree);
        item->setText(0, itemData.value(QStringLiteral("name")).toString());
        item->setText(1, itemData.value(QStringLiteral("status")).toString());
        item->setText(2, itemData.value(QStringLiteral("message")).toString() +
                             (itemData.value(QStringLiteral("details")).toString().isEmpty()
                                      ? QString()
                                      : QStringLiteral(" | ") + itemData.value(QStringLiteral("details")).toString()));
    }
    ui->infraTree->expandAll();
}

void MainWindow::populateSession() {
    // 当前约束面板已移除，不再展示 userBrief / requestSummary
    // 任务书面板已简化，不再展示 planThought / mission

    // finalAnswerGroup 已在 .ui 中隐藏，无需写入

    ui->sessionIdValueLabel->setText(m_client->currentSessionId());
    ui->sessionStatusValueLabel->setText(m_client->sessionStatus());
    ui->updatedAtValueLabel->setText(m_client->updatedAtText());
    ui->errorValueLabel->setText(m_client->errorMessage());

    populateConversationList();
    populateReasoningList();
    populateTaskTree();
    populateKnowledgeList();
    populateTimelineList();
    syncFormWithSnapshot();
}

void MainWindow::populateConversationList() {
    ui->conversationList->clear();
    if (m_client->conversationItems().isEmpty()) {
        auto *emptyItem = new QListWidgetItem(ui->conversationList);
        emptyItem->setFlags(Qt::NoItemFlags);
        emptyItem->setText(QStringLiteral("从这里开始新的对话。你可以像聊天产品那样逐步补充目的地、天数、预算和偏好。"));
        return;
    }

    for (const QVariant &value : m_client->conversationItems()) {
        const QVariantMap itemData = value.toMap();
        auto *item = new QListWidgetItem(ui->conversationList);
        item->setText(QString());
        item->setData(Qt::DisplayRole, QString());
        item->setForeground(Qt::transparent);
        item->setFlags(Qt::ItemIsEnabled);
        QWidget *bubbleWidget = buildConversationBubbleWidget(itemData, ui->conversationList);
        const int availableWidth = qMax(320, ui->conversationList->viewport()->width() - 16);
        bubbleWidget->setFixedWidth(availableWidth);
        bubbleWidget->adjustSize();
        const int bubbleHeight = qMax(bubbleWidget->sizeHint().height(), bubbleWidget->minimumSizeHint().height());
        item->setSizeHint(QSize(availableWidth, qMax(bubbleHeight + 8, 88)));
        ui->conversationList->setItemWidget(item, bubbleWidget);
    }
    ui->conversationList->scrollToBottom();
}

void MainWindow::populateReasoningList() {
    ui->reasoningList->clear();
    for (const QVariant &value : m_client->reasoningItems()) {
        const QVariantMap itemData = value.toMap();
        auto *item = new QListWidgetItem(joinKeyValue(itemData, QStringLiteral("label"), QStringLiteral("time")) +
                                                 QStringLiteral("\n") + itemData.value(QStringLiteral("body")).toString(),
                                         ui->reasoningList);
        item->setToolTip(item->text());
    }
}

void MainWindow::populateTaskTree() {
    ui->taskTree->clear();
    for (const QVariant &value : m_client->taskItems()) {
        const QVariantMap itemData = value.toMap();
        auto *item = new QTreeWidgetItem(ui->taskTree);
        item->setText(0, itemData.value(QStringLiteral("title")).toString());
        item->setText(1, itemData.value(QStringLiteral("source")).toString());
        item->setText(2, itemData.value(QStringLiteral("roundAttempt")).toString());
        item->setText(3, itemData.value(QStringLiteral("batch")).toString());
        item->setText(4, itemData.value(QStringLiteral("mode")).toString());
        item->setText(5, itemData.value(QStringLiteral("status")).toString());
        item->setText(6, itemData.value(QStringLiteral("description")).toString());
        item->setToolTip(0, itemData.value(QStringLiteral("title")).toString());
        item->setToolTip(6, itemData.value(QStringLiteral("description")).toString());
    }
    ui->taskTree->expandAll();
}

void MainWindow::populateKnowledgeList() {
    ui->knowledgeList->clear();
    for (const QVariant &value : m_client->knowledgeHits()) {
        const QVariantMap itemData = value.toMap();
        QString text = itemData.value(QStringLiteral("title")).toString();
        const QString source = itemData.value(QStringLiteral("source")).toString();
        if (!source.isEmpty()) {
            text += QStringLiteral("\n来源: ") + source;
        }
        const QString score = itemData.value(QStringLiteral("score")).toString();
        if (!score.isEmpty()) {
            text += QStringLiteral(" | score=") + score;
        }
        text += QStringLiteral("\n") + itemData.value(QStringLiteral("excerpt")).toString();
        auto *item = new QListWidgetItem(text, ui->knowledgeList);
        item->setToolTip(text);
    }
}

void MainWindow::populateTimelineList() {
    ui->timelineList->clear();
    for (const QVariant &value : m_client->timelineItems()) {
        const QVariantMap itemData = value.toMap();
        auto *item = new QListWidgetItem(joinKeyValue(itemData, QStringLiteral("label"), QStringLiteral("time")) +
                                                 QStringLiteral("\n") + itemData.value(QStringLiteral("body")).toString(),
                                         ui->timelineList);
        item->setToolTip(item->text());
    }
}

void MainWindow::syncFormWithSnapshot() {
    const QVariantMap snapshot = m_client->requestSnapshot();
    if (snapshot.isEmpty()) {
        return;
    }

    const QSignalBlocker departureBlocker(ui->departureEdit);
    ui->departureEdit->setText(snapshot.value(QStringLiteral("departure")).toString());
}

QVariantMap MainWindow::collectPayload() const {
    QVariantMap payload{
            {QStringLiteral("message"), ui->messageEdit->toPlainText()},
            {QStringLiteral("departure"), ui->departureEdit->text()},
            {QStringLiteral("userId"), QStringLiteral("voyu-qt")}
    };
    if (!m_client->activeSessionId().isEmpty()) {
        payload.insert(QStringLiteral("sessionId"), m_client->activeSessionId());
    }
    return payload;
}

QString MainWindow::selectedSessionId() const {
    QListWidgetItem *item = ui->recentSessionsList->currentItem();
    const QString currentItemSessionId = item == nullptr ? QString() : item->data(Qt::UserRole).toString();
    if (!currentItemSessionId.isEmpty()) {
        return currentItemSessionId;
    }
    return m_selectedSessionId;
}

QString MainWindow::buildRequestSummary(const QVariantMap &snapshot) const {
    QStringList lines;
    const QList<QPair<QString, QString>> fields = {
            {QStringLiteral("destination"), QStringLiteral("目的地")},
            {QStringLiteral("departure"), QStringLiteral("出发地")},
            {QStringLiteral("travelDays"), QStringLiteral("行程天数")},
            {QStringLiteral("budget"), QStringLiteral("预算")},
            {QStringLiteral("preferences"), QStringLiteral("偏好")},
            {QStringLiteral("userId"), QStringLiteral("用户标识")}
    };

    for (const auto &field : fields) {
        const QString value = snapshot.value(field.first).toString().trimmed();
        if (!value.isEmpty()) {
            lines.append(field.second + QStringLiteral("：") + value);
        }
    }

    return lines.isEmpty() ? QStringLiteral("还没有会话上下文。") : lines.join(QStringLiteral("\n"));
}

QString MainWindow::joinKeyValue(const QVariantMap &item, const QString &first, const QString &second) {
    const QString left = item.value(first).toString();
    const QString right = item.value(second).toString();
    return right.isEmpty() ? left : left + QStringLiteral(" · ") + right;
}
