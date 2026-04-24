# Voyu Qt Client

这个目录提供 `voyu` 的桌面版客户端原型，使用 `Qt Widgets` 直接复用现有后端接口：

- `POST /api/travel-agent/stream`
- `GET /api/travel-agent/sessions/{sessionId}`
- `GET /api/infrastructure/status`

默认 `baseUrl` 设为 `http://175.178.47.238`，客户端直接通过服务器公网 IP 访问 nginx，再由 nginx 反代到服务器本机 `127.0.0.1:8090`。

当前同时提供两种工程入口：

- `CMakeLists.txt`
- `voyu_desktop.pro`

主界面现在由 [mainwindow.ui](/E:/J/Job/AI/voyu/client-qt/src/mainwindow.ui) 定义，你可以直接用 `Qt Designer / Qt Creator` 可视化调整布局，不需要改 QML。

## 本机构建

建议使用你当前已经安装好的工具链：

- Qt: `D:\Q\Qt\Qt\6.7.3\mingw_64`
- CMake: `D:\Q\Qt\Qt\Tools\CMake_64\bin\cmake.exe`
- Ninja: `D:\Q\Qt\Qt\Tools\Ninja\ninja.exe`
- MinGW: `D:\Q\Qt\Qt\Tools\mingw1310_64\bin`

示例命令：

```powershell
& 'D:\Q\Qt\Qt\Tools\CMake_64\bin\cmake.exe' `
  -S 'E:\J\Job\AI\voyu\client-qt' `
  -B 'E:\J\Job\AI\voyu\client-qt\build' `
  -G Ninja `
  -DCMAKE_PREFIX_PATH='D:\Q\Qt\Qt\6.7.3\mingw_64' `
  -DCMAKE_C_COMPILER='D:\Q\Qt\Qt\Tools\mingw1310_64\bin\gcc.exe' `
  -DCMAKE_CXX_COMPILER='D:\Q\Qt\Qt\Tools\mingw1310_64\bin\g++.exe'

& 'D:\Q\Qt\Qt\Tools\CMake_64\bin\cmake.exe' --build 'E:\J\Job\AI\voyu\client-qt\build'
```

## qmake 构建

如果你想直接用 `.pro`：

```powershell
& 'D:\Q\Qt\Qt\6.7.3\mingw_64\bin\qmake.exe' 'E:\J\Job\AI\voyu\client-qt\voyu_desktop.pro'
& 'D:\Q\Qt\Qt\Tools\mingw1310_64\bin\mingw32-make.exe'
```

界面逻辑位于：

- [mainwindow.ui](/E:/J/Job/AI/voyu/client-qt/src/mainwindow.ui)
- [mainwindow.cpp](/E:/J/Job/AI/voyu/client-qt/src/mainwindow.cpp)
- [voyuclient.cpp](/E:/J/Job/AI/voyu/client-qt/src/voyuclient.cpp)

## 连接方式建议

- 默认使用 `http://175.178.47.238`
- 如果后续域名重新可用，再改回域名入口

当前不走域名，因此不建议使用 `https://IP`。证书按域名校验，Qt 直接连 `https://175.178.47.238` 容易遇到证书主机名不匹配。`8090` 只作为 Spring Boot 在服务器内的上游端口，不作为公网客户端入口。
