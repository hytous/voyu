#pragma once

#include <QMainWindow>
#include <QString>
#include <QVariantMap>

class QListWidgetItem;

class QResizeEvent;

namespace Ui {
class MainWindow;
}

class VoyuClient;

class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(VoyuClient *client, QWidget *parent = nullptr);
    ~MainWindow() override;

protected:
    void resizeEvent(QResizeEvent *event) override;

private slots:
    void refreshFromClient();
    void refreshInfrastructure();
    void submitPlan();
    void abortPlan();
    void clearForm();
    void loadRecentSession(QListWidgetItem *item);
    void loadSelectedSession();
    void deleteSelectedSession();
    void updateSessionButtons();

private:
    void setupUiState();
    void populateRecentSessions();
    void populateInfrastructure();
    void populateSession();
    void populateConversationList();
    void populateReasoningList();
    void populateTaskTree();
    void populateKnowledgeList();
    void populateTimelineList();
    void syncFormWithSnapshot();
    QVariantMap collectPayload() const;
    QString selectedSessionId() const;
    QString buildRequestSummary(const QVariantMap &snapshot) const;
    static QString joinKeyValue(const QVariantMap &item, const QString &first, const QString &second);

    Ui::MainWindow *ui;
    VoyuClient *m_client;

    QString m_sessionFilter;
    QString m_selectedSessionId;
};
