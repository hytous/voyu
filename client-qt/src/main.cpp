#include <QCoreApplication>
#include <QApplication>

#include "mainwindow.h"
#include "voyuclient.h"

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);

    QCoreApplication::setOrganizationName("Voyu");
    QCoreApplication::setApplicationName("VoyuDesktop");

    VoyuClient client;
    MainWindow window(&client);
    window.show();

    return app.exec();
}
