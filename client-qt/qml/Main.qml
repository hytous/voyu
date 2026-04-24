import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window

    width: 1560
    height: 960
    visible: true
    title: "Voyu Desktop"
    color: "#efe7d7"

    property var templates: [
        {
            "title": "大阪 4 天美食动漫",
            "message": "我想五一从上海去大阪玩 4 天，预算 7000，喜欢动漫、美食和轻松步行，请帮我做一个节奏合理的路线。",
            "destination": "大阪",
            "departure": "上海",
            "travelDays": "4 天",
            "budget": "7000",
            "preferences": "动漫,美食,轻松步行"
        },
        {
            "title": "东京家庭慢游",
            "message": "6 月中旬带父母去东京 5 天，预算 12000，希望交通方便、景点不要太赶，兼顾购物和城市观光。",
            "destination": "东京",
            "departure": "杭州",
            "travelDays": "5 天",
            "budget": "12000",
            "preferences": "家庭出行,轻松行程,购物"
        },
        {
            "title": "京都摄影慢游",
            "message": "秋天想去京都慢游 3 天，偏好寺庙、摄影、日式街区和安静的咖啡店，请帮我设计拍照友好的路线。",
            "destination": "京都",
            "departure": "深圳",
            "travelDays": "3 天",
            "budget": "6500",
            "preferences": "摄影,寺庙,咖啡,慢游"
        }
    ]

    function fillTemplate(data) {
        messageInput.text = data.message
        destinationInput.text = data.destination
        departureInput.text = data.departure
        travelDaysInput.text = data.travelDays
        budgetInput.text = data.budget
        preferencesInput.text = data.preferences
    }

    function submitPlan() {
        voyuClient.baseUrl = baseUrlField.text
        voyuClient.startPlan({
            "message": messageInput.text,
            "destination": destinationInput.text,
            "departure": departureInput.text,
            "travelDays": travelDaysInput.text,
            "budget": budgetInput.text,
            "preferences": preferencesInput.text,
            "userId": "voyu-qt"
        })
    }

    Component.onCompleted: voyuClient.refreshInfrastructure()

    header: Rectangle {
        height: 84
        color: "#fff8ef"
        border.color: "#d7ccb8"

        RowLayout {
            anchors.fill: parent
            anchors.margins: 18
            spacing: 16

            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true

                Label {
                    text: "Spring AI Travel Planner"
                    font.pixelSize: 12
                    color: "#8a7862"
                }

                Label {
                    text: "Voyu 桌面工作台"
                    font.pixelSize: 28
                    font.bold: true
                    color: "#231f18"
                }
            }

            TextField {
                id: baseUrlField
                Layout.preferredWidth: 340
                text: voyuClient.baseUrl
                placeholderText: "http://175.178.47.238"
                onEditingFinished: voyuClient.baseUrl = text
            }

            Button {
                text: "刷新中间件"
                onClicked: {
                    voyuClient.baseUrl = baseUrlField.text
                    voyuClient.refreshInfrastructure()
                }
            }

            Rectangle {
                radius: 18
                color: voyuClient.streaming ? "#d8f1ec" : "#f4eee5"
                border.color: voyuClient.streaming ? "#55a899" : "#d7ccb8"
                Layout.preferredHeight: 38
                Layout.preferredWidth: 140

                Label {
                    anchors.centerIn: parent
                    text: voyuClient.streamStatus
                    color: voyuClient.streaming ? "#0c625a" : "#5e564a"
                    font.pixelSize: 14
                }
            }
        }
    }

    SplitView {
        anchors.fill: parent
        orientation: Qt.Horizontal

        Rectangle {
            SplitView.preferredWidth: 280
            SplitView.minimumWidth: 240
            color: "#241c16"

            ScrollView {
                anchors.fill: parent
                anchors.margins: 18
                clip: true

                Column {
                    width: parent.availableWidth
                    spacing: 18

                    Rectangle {
                        width: parent.width
                        radius: 18
                        color: "#342920"
                        border.color: "#4c3c2f"
                        height: 96

                        Column {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 6

                            Label {
                                text: "Travel Agent Workspace"
                                color: "#b8a28a"
                                font.pixelSize: 12
                            }

                            Label {
                                text: "Voyu"
                                color: "#fff7ee"
                                font.pixelSize: 30
                                font.bold: true
                            }
                        }
                    }

                    Button {
                        width: parent.width
                        text: "新建旅程"
                        onClicked: {
                            messageInput.text = ""
                            destinationInput.text = ""
                            departureInput.text = ""
                            travelDaysInput.text = ""
                            budgetInput.text = ""
                            preferencesInput.text = ""
                        }
                    }

                    Rectangle {
                        width: parent.width
                        radius: 18
                        color: "#30261f"
                        border.color: "#4c3c2f"

                        Column {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 12

                            Label {
                                text: "本机最近旅程"
                                color: "#fff7ee"
                                font.pixelSize: 16
                                font.bold: true
                            }

                            Repeater {
                                model: voyuClient.recentSessions

                                Rectangle {
                                    width: parent.width
                                    radius: 14
                                    color: "#3c2f25"
                                    border.color: "#5a4738"
                                    implicitHeight: 82

                                    MouseArea {
                                        anchors.fill: parent
                                        onClicked: {
                                            voyuClient.baseUrl = baseUrlField.text
                                            voyuClient.loadSession(modelData.sessionId)
                                        }
                                    }

                                    Column {
                                        anchors.fill: parent
                                        anchors.margins: 12
                                        spacing: 6

                                        Label {
                                            width: parent.width
                                            text: modelData.title
                                            color: "#fff7ee"
                                            wrapMode: Text.Wrap
                                            font.pixelSize: 14
                                        }

                                        Label {
                                            text: (modelData.destination || "未填目的地") + " · " + (modelData.travelDays || modelData.status)
                                            color: "#c7b39c"
                                            font.pixelSize: 12
                                        }

                                        Label {
                                            text: modelData.updatedAt || "-"
                                            color: "#9a8772"
                                            font.pixelSize: 11
                                        }
                                    }
                                }
                            }

                            Label {
                                visible: voyuClient.recentSessions.length === 0
                                text: "当前还没有保存过旅程。"
                                color: "#b8a28a"
                                wrapMode: Text.Wrap
                            }
                        }
                    }

                    Rectangle {
                        width: parent.width
                        radius: 18
                        color: "#30261f"
                        border.color: "#4c3c2f"

                        Column {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 12

                            Label {
                                text: "快速模板"
                                color: "#fff7ee"
                                font.pixelSize: 16
                                font.bold: true
                            }

                            Repeater {
                                model: window.templates

                                Button {
                                    width: parent.width
                                    text: modelData.title
                                    onClicked: fillTemplate(modelData)
                                }
                            }
                        }
                    }
                }
            }
        }

        ScrollView {
            id: centerScroll
            SplitView.fillWidth: true
            clip: true

            ColumnLayout {
                width: centerScroll.availableWidth
                spacing: 16
                anchors.margins: 18

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: 140

                    Column {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 10

                        Label {
                            text: "User Brief"
                            color: "#8a7862"
                            font.pixelSize: 12
                        }

                        Label {
                            width: parent.width
                            text: voyuClient.userBrief
                            wrapMode: Text.Wrap
                            color: "#231f18"
                            font.pixelSize: 20
                            font.bold: true
                        }

                        Flow {
                            width: parent.width
                            spacing: 8

                            Repeater {
                                model: voyuClient.requestChips

                                Rectangle {
                                    radius: 14
                                    color: "#f4eee5"
                                    border.color: "#d7ccb8"
                                    height: 30
                                    width: chipText.implicitWidth + 18

                                    Label {
                                        id: chipText
                                        anchors.centerIn: parent
                                        text: modelData.label + " · " + modelData.value
                                        color: "#5e564a"
                                        font.pixelSize: 12
                                    }
                                }
                            }
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: reasoningColumn.implicitHeight + 36

                    Column {
                        id: reasoningColumn
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "思考过程"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Repeater {
                            model: voyuClient.reasoningItems

                            Rectangle {
                                width: parent.width
                                radius: 16
                                color: "#f7f1e6"
                                border.color: "#e1d6c3"
                                implicitHeight: bodyLabel.implicitHeight + 40

                                Column {
                                    anchors.fill: parent
                                    anchors.margins: 14
                                    spacing: 8

                                    RowLayout {
                                        width: parent.width

                                        Label {
                                            text: modelData.label
                                            font.bold: true
                                            color: "#0f766e"
                                        }

                                        Item {
                                            Layout.fillWidth: true
                                        }

                                        Label {
                                            text: modelData.time
                                            color: "#8a7862"
                                            font.pixelSize: 12
                                        }
                                    }

                                    Label {
                                        id: bodyLabel
                                        width: parent.width
                                        text: modelData.body
                                        wrapMode: Text.Wrap
                                        color: "#302821"
                                    }
                                }
                            }
                        }

                        Label {
                            visible: voyuClient.reasoningItems.length === 0
                            text: "思考链路会在这里拆成可读步骤。"
                            color: "#8a7862"
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: planColumn.implicitHeight + 36

                    Column {
                        id: planColumn
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "规划思路与任务书"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Rectangle {
                            width: parent.width
                            radius: 16
                            color: "#e9f5f1"
                            border.color: "#add4ca"
                            implicitHeight: planThoughtLabel.implicitHeight + 28

                            Label {
                                id: planThoughtLabel
                                anchors.fill: parent
                                anchors.margins: 14
                                text: voyuClient.planThought
                                wrapMode: Text.Wrap
                                color: "#0b5b55"
                            }
                        }

                        Rectangle {
                            width: parent.width
                            radius: 16
                            color: "#f7f1e6"
                            border.color: "#e1d6c3"
                            implicitHeight: missionLabel.implicitHeight + 28

                            Label {
                                id: missionLabel
                                anchors.fill: parent
                                anchors.margins: 14
                                text: voyuClient.mission
                                wrapMode: Text.Wrap
                                color: "#302821"
                            }
                        }

                        Repeater {
                            model: voyuClient.taskItems

                            Rectangle {
                                width: parent.width
                                radius: 16
                                color: "#f7f1e6"
                                border.color: "#e1d6c3"
                                implicitHeight: taskDescription.implicitHeight + 44

                                Column {
                                    anchors.fill: parent
                                    anchors.margins: 14
                                    spacing: 8

                                    RowLayout {
                                        width: parent.width

                                        Label {
                                            text: modelData.title
                                            color: "#231f18"
                                            font.bold: true
                                        }

                                        Item {
                                            Layout.fillWidth: true
                                        }

                                        Rectangle {
                                            radius: 12
                                            color: "#f0e7d8"
                                            border.color: "#d7ccb8"
                                            implicitWidth: statusText.implicitWidth + 16
                                            implicitHeight: 28

                                            Label {
                                                id: statusText
                                                anchors.centerIn: parent
                                                text: modelData.status
                                                color: "#5e564a"
                                                font.pixelSize: 12
                                            }
                                        }
                                    }

                                    Label {
                                        id: taskDescription
                                        width: parent.width
                                        text: modelData.description + (modelData.parallel ? " · 可并行" : "")
                                        wrapMode: Text.Wrap
                                        color: "#5e564a"
                                    }
                                }
                            }
                        }

                        Label {
                            visible: voyuClient.taskItems.length === 0
                            text: "还没有生成任务书。"
                            color: "#8a7862"
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: answerText.implicitHeight + 56

                    Column {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "旅行方案"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Text {
                            id: answerText
                            width: parent.width
                            text: voyuClient.finalAnswer.length > 0 ? voyuClient.finalAnswer : "最终行程方案会在执行完成后出现在这里。"
                            textFormat: Text.MarkdownText
                            wrapMode: Text.Wrap
                            color: "#302821"
                            font.pixelSize: 15
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: knowledgeColumn.implicitHeight + 36

                    Column {
                        id: knowledgeColumn
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "检索依据"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Repeater {
                            model: voyuClient.knowledgeHits

                            Rectangle {
                                width: parent.width
                                radius: 16
                                color: "#f7f1e6"
                                border.color: "#e1d6c3"
                                implicitHeight: excerptText.implicitHeight + 46

                                Column {
                                    anchors.fill: parent
                                    anchors.margins: 14
                                    spacing: 8

                                    RowLayout {
                                        width: parent.width

                                        Label {
                                            text: modelData.title
                                            color: "#231f18"
                                            font.bold: true
                                        }

                                        Item {
                                            Layout.fillWidth: true
                                        }

                                        Label {
                                            text: modelData.score === undefined ? "" : ("score=" + modelData.score)
                                            color: "#8a7862"
                                            font.pixelSize: 12
                                        }
                                    }

                                    Label {
                                        text: modelData.source || ""
                                        color: "#0f766e"
                                        font.pixelSize: 12
                                    }

                                    Label {
                                        id: excerptText
                                        width: parent.width
                                        text: modelData.excerpt
                                        wrapMode: Text.Wrap
                                        color: "#5e564a"
                                    }
                                }
                            }
                        }

                        Label {
                            visible: voyuClient.knowledgeHits.length === 0
                            text: "当知识检索返回结果时，这里会展示命中的知识片段。"
                            color: "#8a7862"
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"
                    implicitHeight: timelineColumn.implicitHeight + 36

                    Column {
                        id: timelineColumn
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "流式事件时间线"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Repeater {
                            model: voyuClient.timelineItems

                            Rectangle {
                                width: parent.width
                                radius: 16
                                color: modelData.tone === "warning" ? "#fff3e8" : (modelData.tone === "success" ? "#e9f5f1" : "#f7f1e6")
                                border.color: modelData.tone === "warning" ? "#e9b27f" : (modelData.tone === "success" ? "#add4ca" : "#e1d6c3")
                                implicitHeight: timelineBody.implicitHeight + 42

                                Column {
                                    anchors.fill: parent
                                    anchors.margins: 14
                                    spacing: 8

                                    RowLayout {
                                        width: parent.width

                                        Label {
                                            text: modelData.label
                                            font.bold: true
                                            color: "#231f18"
                                        }

                                        Item {
                                            Layout.fillWidth: true
                                        }

                                        Label {
                                            text: modelData.time
                                            color: "#8a7862"
                                            font.pixelSize: 12
                                        }
                                    }

                                    Label {
                                        id: timelineBody
                                        width: parent.width
                                        text: modelData.body
                                        wrapMode: Text.Wrap
                                        color: "#5e564a"
                                    }
                                }
                            }
                        }

                        Label {
                            visible: voyuClient.timelineItems.length === 0
                            text: "还没有事件。"
                            color: "#8a7862"
                        }
                    }
                }
            }
        }

        ScrollView {
            id: rightScroll
            SplitView.preferredWidth: 360
            SplitView.minimumWidth: 320
            clip: true

            Column {
                width: rightScroll.availableWidth
                spacing: 16
                anchors.margins: 18

                Rectangle {
                    width: parent.width
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"

                    Column {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "中间件状态"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Repeater {
                            model: voyuClient.infrastructureItems

                            Rectangle {
                                width: parent.width
                                radius: 16
                                color: modelData.status === "UP" ? "#e9f5f1" : "#fff3e8"
                                border.color: modelData.status === "UP" ? "#add4ca" : "#e9b27f"
                                implicitHeight: infraDetails.implicitHeight + 44

                                Column {
                                    anchors.fill: parent
                                    anchors.margins: 14
                                    spacing: 8

                                    RowLayout {
                                        width: parent.width

                                        Label {
                                            text: modelData.name
                                            font.bold: true
                                            color: "#231f18"
                                        }

                                        Item {
                                            Layout.fillWidth: true
                                        }

                                        Label {
                                            text: modelData.status
                                            color: modelData.status === "UP" ? "#0b5b55" : "#b45309"
                                            font.pixelSize: 12
                                            font.bold: true
                                        }
                                    }

                                    Label {
                                        text: modelData.message
                                        color: "#302821"
                                        wrapMode: Text.Wrap
                                    }

                                    Label {
                                        id: infraDetails
                                        width: parent.width
                                        text: modelData.details
                                        color: "#8a7862"
                                        wrapMode: Text.Wrap
                                        font.pixelSize: 12
                                    }
                                }
                            }
                        }
                    }
                }

                Rectangle {
                    width: parent.width
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"

                    Column {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 10

                        Label {
                            text: "当前会话"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        Label {
                            text: "Session ID"
                            color: "#8a7862"
                            font.pixelSize: 12
                        }

                        Text {
                            width: parent.width
                            text: voyuClient.currentSessionId
                            wrapMode: Text.WrapAnywhere
                            color: "#231f18"
                        }

                        Label {
                            text: "状态: " + voyuClient.sessionStatus
                            color: "#302821"
                        }

                        Label {
                            text: "更新时间: " + voyuClient.updatedAtText
                            color: "#302821"
                        }

                        Label {
                            width: parent.width
                            text: "错误: " + voyuClient.errorMessage
                            color: voyuClient.errorMessage === "无" ? "#302821" : "#b45309"
                            wrapMode: Text.Wrap
                        }
                    }
                }

                Rectangle {
                    width: parent.width
                    radius: 22
                    color: "#fff8ef"
                    border.color: "#d7ccb8"

                    Column {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 12

                        Label {
                            text: "请求表单"
                            color: "#231f18"
                            font.pixelSize: 22
                            font.bold: true
                        }

                        TextArea {
                            id: messageInput
                            width: parent.width
                            height: 120
                            placeholderText: "例如：五一从上海出发去大阪 4 天，预算 7000，想兼顾动漫、美食和轻松步行。"
                            wrapMode: TextArea.Wrap
                        }

                        TextField {
                            id: destinationInput
                            width: parent.width
                            placeholderText: "目的地"
                        }

                        TextField {
                            id: departureInput
                            width: parent.width
                            placeholderText: "出发地"
                        }

                        TextField {
                            id: travelDaysInput
                            width: parent.width
                            placeholderText: "天数"
                        }

                        TextField {
                            id: budgetInput
                            width: parent.width
                            placeholderText: "预算"
                        }

                        TextField {
                            id: preferencesInput
                            width: parent.width
                            placeholderText: "偏好"
                        }

                        RowLayout {
                            width: parent.width

                            Button {
                                Layout.fillWidth: true
                                text: "开始规划"
                                onClicked: submitPlan()
                            }

                            Button {
                                Layout.fillWidth: true
                                text: "中止流式任务"
                                enabled: voyuClient.streaming
                                onClicked: voyuClient.abortStream()
                            }
                        }

                        Label {
                            width: parent.width
                            text: "客户端直接调用 /api/travel-agent/stream、/api/travel-agent/sessions/{id} 和 /api/infrastructure/status。"
                            color: "#8a7862"
                            wrapMode: Text.Wrap
                            font.pixelSize: 12
                        }
                    }
                }
            }
        }
    }
}
