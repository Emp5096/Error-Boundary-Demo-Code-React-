# Excel Export Incident Lab

完整操作手册同时打包在网页静态资源中：启动应用后访问：

- 实验页：<http://127.0.0.1:8090/export-lab.html>
- 命令手册：<http://127.0.0.1:8090/EXPORT-LAB-GUIDE.md>

最短启动流程：

```powershell
docker compose -f docker-compose.export-lab.yml up -d
mvn -pl demo-app -am package
.\scripts\start-export-lab.ps1 -HeapMb 384
```

实验包含三条互不混淆的路径：普通正确导出、遗漏 break 的轻量空转、以及“重复提交 + 通用 8 线程执行器 + 窗口聚合重 SQL + 遗漏退出”的级联拥塞。另有 byte[] 注入只用于稳定生成 `.hprof`。
