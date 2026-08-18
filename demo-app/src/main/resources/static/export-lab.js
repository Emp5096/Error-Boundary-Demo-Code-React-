(() => {
    'use strict';

    const $ = selector => document.querySelector(selector);
    const elements = {
        service: $('#service-status'), pid: $('#pid'), copyPid: $('#copy-pid'),
        cpu: $('#metric-cpu'), heap: $('#metric-heap'), heapDetail: $('#metric-heap-detail'),
        gc: $('#metric-gc'), gcDetail: $('#metric-gc-detail'), pool: $('#metric-pool'),
        hikari: $('#metric-hikari'), queries: $('#metric-queries'), threads: $('#metric-threads'),
        temp: $('#metric-temp'), output: $('#metric-output'), tempPath: $('#temp-path'), outputPath: $('#output-path'),
        statusRt: $('#metric-status-rt'), heapLine: $('#heap-line'), cpuLine: $('#cpu-line'),
        chartSummary: $('#chart-summary'), dataCount: $('#data-count'), seedForm: $('#seed-form'),
        seedRows: $('#seed-rows'), seedButton: $('#seed-button'), seedHelp: $('#seed-help'),
        taskForm: $('#task-form'), mode: $('#mode'), modeHelp: $('#mode-help'), queryStrategy: $('#query-strategy'), queryHelp: $('#query-help'), pageSize: $('#page-size'),
        fileLimit: $('#file-limit'), duration: $('#duration'), duplicateCount: $('#duplicate-count'), saveFiles: $('#save-files'),
        oomKb: $('#oom-kb'), oomConfirm: $('#oom-confirm'), startButton: $('#start-button'),
        taskMessage: $('#task-message'), taskCount: $('#task-count'), taskTable: $('#task-table'),
        threadDumpButton: $('#thread-dump-button'), copyLinux: $('#copy-linux'), evidence: $('#evidence-preview'),
        probeRunState: $('#probe-run-state'), probeInterval: $('#probe-interval'), probeLastId: $('#probe-last-id'),
        probeRowLimit: $('#probe-row-limit'), probeStart: $('#probe-start'), probeStop: $('#probe-stop'),
        probeBaseline: $('#probe-baseline'), probeReset: $('#probe-reset'), probeMessage: $('#probe-message'),
        toast: $('#toast')
    };

    const integer = new Intl.NumberFormat('zh-CN');
    let latest = null;
    let polling = false;
    let toastTimer;
    let probeTimer = null;
    let probeRunning = false;
    const probeSeries = Object.fromEntries(['jvm', 'mysql', 'table'].map(name => [name, {
        samples: [], success: 0, errors: 0, last: null, inFlight: false
    }]));
    const probeBaselines = { jvm: null, mysql: null, table: null };

    function bytes(value) {
        if (value == null || value < 0) return '不可用';
        const units = ['B', 'KB', 'MB', 'GB'];
        let size = value;
        let index = 0;
        while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; }
        return `${size >= 100 || index === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`;
    }

    function elapsed(ms) {
        if (ms < 1000) return `${ms} ms`;
        if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
        return `${Math.floor(ms / 60_000)}m ${Math.floor(ms % 60_000 / 1000)}s`;
    }

    function showToast(message) {
        clearTimeout(toastTimer);
        elements.toast.textContent = message;
        elements.toast.classList.add('show');
        toastTimer = setTimeout(() => elements.toast.classList.remove('show'), 4000);
    }

    async function request(url, options = {}) {
        const response = await fetch(url, {
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
            ...options
        });
        const text = await response.text();
        let body = null;
        if (text) {
            try { body = JSON.parse(text); } catch { body = { message: text }; }
        }
        if (!response.ok) throw new Error(body?.message || `请求失败：HTTP ${response.status}`);
        return body;
    }

    function render(data) {
        latest = data;
        elements.service.className = 'health online';
        elements.service.innerHTML = '<span class="health-dot"></span>Spring Boot 在线';
        elements.pid.textContent = data.pid;
        const m = data.runtime;
        const heapRatio = m.heapMaxBytes > 0 ? m.heapUsedBytes / m.heapMaxBytes : 0;
        elements.cpu.textContent = m.processCpuPercent < 0 ? '不可用' : `${m.processCpuPercent.toFixed(1)}%`;
        elements.heap.textContent = `${(heapRatio * 100).toFixed(1)}%`;
        elements.heapDetail.textContent = `${bytes(m.heapUsedBytes)} / ${bytes(m.heapMaxBytes)}`;
        elements.gc.textContent = `${integer.format(m.gcCount)} 次`;
        elements.gcDetail.textContent = `累计停顿 ${integer.format(m.gcTimeMillis)} ms`;
        elements.pool.textContent = `${m.poolActive} active · Q${m.poolQueue}`;
        elements.hikari.textContent = `${m.hikariActive} / ${m.hikariIdle}`;
        elements.queries.textContent = integer.format(m.totalQueries);
        elements.threads.textContent = integer.format(m.liveThreads);
        elements.temp.textContent = bytes(m.tempBytes);
        elements.output.textContent = bytes(m.outputBytes);
        elements.tempPath.textContent = data.tempDirectory;
        elements.outputPath.textContent = data.outputDirectory;
        elements.dataCount.textContent = data.databaseRows < 0 ? 'MySQL 不可用' : `${integer.format(data.databaseRows)} 行`;
        renderChart(data.samples || []);
        renderTasks(data.tasks || []);
    }

    function renderChart(samples) {
        if (!samples.length) {
            elements.heapLine.setAttribute('points', '');
            elements.cpuLine.setAttribute('points', '');
            elements.chartSummary.textContent = '等待第一组 JVM 采样。';
            return;
        }
        const chart = (selector) => samples.map((sample, index) => {
            const x = samples.length === 1 ? 0 : index * 1000 / (samples.length - 1);
            const ratio = Math.max(0, Math.min(1, selector(sample)));
            const y = 240 - ratio * 220;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        }).join(' ');
        elements.heapLine.setAttribute('points', chart(sample => sample.heapMaxBytes > 0 ? sample.heapUsedBytes / sample.heapMaxBytes : 0));
        elements.cpuLine.setAttribute('points', chart(sample => Math.max(0, sample.processCpuPercent) / 100));
        const last = samples[samples.length - 1];
        const first = samples[0];
        const heapPercent = last.heapMaxBytes > 0 ? last.heapUsedBytes * 100 / last.heapMaxBytes : 0;
        elements.chartSummary.textContent = `从 ${new Date(first.timestamp).toLocaleTimeString()} 到 ${new Date(last.timestamp).toLocaleTimeString()}：当前 CPU ${last.processCpuPercent.toFixed(1)}%，Heap ${heapPercent.toFixed(1)}%，累计 GC ${last.gcCount} 次。`;
    }

    function percentile(values, ratio) {
        if (!values.length) return null;
        const sorted = [...values].sort((left, right) => left - right);
        return sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)];
    }

    function summarizeProbe(series) {
        if (!series.samples.length) return null;
        const total = series.samples.reduce((sum, value) => sum + value, 0);
        return {
            avg: total / series.samples.length,
            p95: percentile(series.samples, 0.95),
            p99: percentile(series.samples, 0.99)
        };
    }

    function rt(value) {
        if (value == null) return '—';
        return `${value < 10 ? value.toFixed(1) : Math.round(value)} ms`;
    }

    function renderProbeStats() {
        for (const name of ['jvm', 'mysql', 'table']) {
            const series = probeSeries[name];
            const summary = summarizeProbe(series);
            $(`#probe-${name}-current`).textContent = rt(series.last);
            $(`#probe-${name}-avg`).textContent = rt(summary?.avg);
            $(`#probe-${name}-p95`).textContent = rt(summary?.p95);
            $(`#probe-${name}-p99`).textContent = rt(summary?.p99);
            $(`#probe-${name}-meta`).textContent = `成功 ${integer.format(series.success)} · 错误 ${integer.format(series.errors)}`;
            const baseline = probeBaselines[name];
            const delta = baseline && summary ? summary.p95 - baseline.p95 : null;
            $(`#probe-${name}-baseline`).textContent = baseline
                ? `基线 P95 ${rt(baseline.p95)}${delta == null ? '' : ` · 当前 ${delta >= 0 ? '+' : ''}${rt(delta)}`}`
                : '基线：未保存';
        }
    }

    function resetProbeSeries() {
        for (const series of Object.values(probeSeries)) {
            series.samples.length = 0;
            series.success = 0;
            series.errors = 0;
            series.last = null;
        }
        renderProbeStats();
    }

    async function sampleOneProbe(name, url) {
        const series = probeSeries[name];
        if (series.inFlight) return;
        series.inFlight = true;
        const started = performance.now();
        try {
            await request(url);
            const measured = performance.now() - started;
            series.last = measured;
            series.samples.push(measured);
            if (series.samples.length > 2000) series.samples.shift();
            series.success++;
        } catch {
            series.errors++;
        } finally {
            series.inFlight = false;
        }
    }

    async function runProbeRound() {
        if (!probeRunning) return;
        const lastId = Math.max(0, Number(elements.probeLastId.value) || 0);
        const limit = Math.min(500, Math.max(1, Number(elements.probeRowLimit.value) || 100));
        await Promise.all([
            sampleOneProbe('jvm', '/api/export-lab/probe/jvm'),
            sampleOneProbe('mysql', '/api/export-lab/probe/mysql'),
            sampleOneProbe('table', `/api/export-lab/probe/table?lastId=${encodeURIComponent(lastId)}&limit=${encodeURIComponent(limit)}`)
        ]);
        renderProbeStats();
        if (probeRunning) {
            probeTimer = setTimeout(runProbeRound, Number(elements.probeInterval.value));
        }
    }

    function startProbes() {
        if (probeRunning) return;
        probeRunning = true;
        elements.probeStart.disabled = true;
        elements.probeStop.disabled = false;
        elements.probeRunState.className = 'badge running';
        elements.probeRunState.textContent = '持续采样中';
        elements.probeMessage.textContent = '每轮同时发送纯 JVM、SELECT 1、同表业务查询各一次；统计的是浏览器完整往返时间。';
        runProbeRound();
    }

    function stopProbes() {
        probeRunning = false;
        clearTimeout(probeTimer);
        probeTimer = null;
        elements.probeStart.disabled = false;
        elements.probeStop.disabled = true;
        elements.probeRunState.className = 'badge neutral';
        elements.probeRunState.textContent = '已暂停';
    }

    function saveProbeBaseline() {
        const summaries = Object.fromEntries(Object.entries(probeSeries).map(([name, series]) => [name, summarizeProbe(series)]));
        if (Object.values(summaries).some(summary => summary == null)) {
            showToast('三类探针都至少成功一次后才能保存基线');
            return;
        }
        for (const name of ['jvm', 'mysql', 'table']) probeBaselines[name] = summaries[name];
        resetProbeSeries();
        elements.probeMessage.textContent = '基线已保存且当前样本已清零。现在可以启动错误导出任务，观察当前 P95 与基线的差值。';
        showToast('已保存三类探针基线');
    }

    function statusClass(status) {
        if (status === 'RUNNING' || status === 'QUEUED') return 'running';
        if (status === 'FAILED' || status === 'OOM') return 'failed';
        return 'done';
    }

    function renderTasks(tasks) {
        elements.taskCount.textContent = `${tasks.length} 个任务`;
        if (!tasks.length) {
            elements.taskTable.innerHTML = '<tr><td colspan="7" class="empty">暂无任务</td></tr>';
            return;
        }
        elements.taskTable.replaceChildren(...tasks.map(task => {
            const row = document.createElement('tr');
            const stoppable = task.status === 'RUNNING' || task.status === 'QUEUED';
            const positionLabel = task.queryStrategy === 'UNINDEXED_DEEP_OFFSET' ? 'OFFSET' : '游标';
            row.innerHTML = `
                <td><span class="badge ${statusClass(task.status)}">${task.status}</span><small>${escapeHtml(task.message || '')}</small></td>
                <td>${task.mode}<small>${task.queryStrategy} · ${task.currentPhase}</small></td>
                <td>${integer.format(task.rowsWritten)} 行<small>${task.filesWritten} 文件 · ${bytes(task.outputBytes)}</small></td>
                <td>${integer.format(task.queryCount)}<small>空查询 ${integer.format(task.emptyQueryCount)}</small></td>
                <td>${integer.format(task.outerLoops)}<small>${positionLabel} ${integer.format(task.cursorId)}</small></td>
                <td>${elapsed(task.elapsedMillis)}<small>${task.retainedBytes ? `额外保留 ${bytes(task.retainedBytes)}` : '无额外堆注入'}</small></td>
                <td></td>`;
            if (stoppable) {
                const button = document.createElement('button');
                button.className = 'button danger compact';
                button.type = 'button';
                button.textContent = '停止';
                button.addEventListener('click', () => stopTask(task.id, button));
                row.lastElementChild.append(button);
            } else {
                row.lastElementChild.textContent = '—';
            }
            return row;
        }));
    }

    function escapeHtml(value) {
        return String(value).replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[char]);
    }

    async function poll() {
        if (polling) return;
        polling = true;
        const started = performance.now();
        try {
            render(await request('/api/export-lab/status'));
            elements.statusRt.textContent = `${Math.round(performance.now() - started)} ms`;
        } catch (error) {
            elements.service.className = 'health offline';
            elements.service.innerHTML = '<span class="health-dot"></span>服务不可连接';
            elements.statusRt.textContent = `>${Math.round(performance.now() - started)} ms / 失败`;
        } finally {
            polling = false;
        }
    }

    async function seed(event) {
        event.preventDefault();
        elements.seedButton.disabled = true;
        elements.seedButton.textContent = '生成中…';
        elements.seedHelp.textContent = '正在通过 MyBatis 分批插入；不要重复点击。';
        try {
            const result = await request(`/api/export-lab/data/seed?targetRows=${encodeURIComponent(elements.seedRows.value)}`, { method: 'POST' });
            elements.seedHelp.textContent = `完成：当前 ${integer.format(result.rows)} 行，最大 ID ${integer.format(result.maxId)}。`;
            showToast('MySQL 测试数据准备完成');
            await poll();
        } catch (error) {
            elements.seedHelp.textContent = `${error.message}。请确认 Docker MySQL 已启动。`;
        } finally {
            elements.seedButton.disabled = false;
            elements.seedButton.textContent = '生成测试数据';
        }
    }

    async function submitTask(event) {
        event.preventDefault();
        const oomKb = Number(elements.oomKb.value);
        const buggy = elements.mode.value === 'BUGGY_MISSING_BREAK';
        const heavySql = elements.queryStrategy.value === 'UNINDEXED_DEEP_OFFSET';
        const duplicateCount = Number(elements.duplicateCount.value);
        const unlimited = Number(elements.duration.value) === 0;
        const warning = oomKb > 0
            ? '这会额外保留堆对象，使用小 Xmx 和 ExitOnOutOfMemoryError 启动时会让整个服务退出。确认启动？'
            : heavySql
                ? `未索引排序 + 深 OFFSET 会让 MySQL 反复扫描、排序并丢弃大量行。${duplicateCount > 1 ? `本次会提交 ${duplicateCount} 个独立任务。` : ''}${buggy ? '遗漏退出后，末页重查询还会继续执行。' : ''}${unlimited ? '当前没有自动停止时限，停止按钮也必须等待正在执行的 SQL 返回。' : ''}确认启动？`
            : buggy ? `错误任务会持续执行空查询和空外循环，但不保证让网站卡顿或触发 OOM。${duplicateCount > 1 ? `本次会提交 ${duplicateCount} 个独立任务。` : ''}${unlimited ? '当前没有自动停止时限，只能手动停止任务或终止 JVM。' : '达到保护时长后会停止。'}确认启动？` : null;
        if (warning && !window.confirm(warning)) return;
        elements.startButton.disabled = true;
        elements.taskMessage.textContent = '正在提交到应用异步执行器…';
        try {
            const requestBody = JSON.stringify({
                    mode: elements.mode.value,
                    queryStrategy: elements.queryStrategy.value,
                    pageSize: Number(elements.pageSize.value),
                    fileRowLimit: Number(elements.fileLimit.value),
                    maxDurationSeconds: Number(elements.duration.value),
                    saveFiles: elements.saveFiles.checked,
                    oomInjectionKbPerOuterLoop: oomKb,
                    dangerConfirmation: elements.oomConfirm.value
                });
            const tasks = await Promise.all(Array.from({ length: duplicateCount }, () => request('/api/export-lab/tasks', {
                method: 'POST',
                body: requestBody
            })));
            elements.taskMessage.textContent = `已提交 ${tasks.length} 个任务：${tasks.map(task => task.id.slice(0, 8)).join('、')}。`;
            showToast(`${tasks.length} 个导出任务已提交`);
            await poll();
        } catch (error) {
            elements.taskMessage.textContent = error.message;
        } finally {
            elements.startButton.disabled = false;
        }
    }

    async function stopTask(id, button) {
        button.disabled = true;
        try {
            await request(`/api/export-lab/tasks/${id}`, { method: 'DELETE' });
            showToast('停止请求已发送');
            await poll();
        } catch (error) {
            showToast(error.message);
            button.disabled = false;
        }
    }

    async function captureThreadDump() {
        elements.threadDumpButton.disabled = true;
        elements.threadDumpButton.textContent = '采集中…';
        try {
            const evidence = await request('/api/export-lab/diagnostics/thread-dump', { method: 'POST' });
            elements.evidence.textContent = `文件：${evidence.path}\n等价命令：${evidence.commandEquivalent}\n\n${evidence.preview}`;
            showToast('线程快照已保存到磁盘');
        } catch (error) {
            elements.evidence.textContent = error.message;
        } finally {
            elements.threadDumpButton.disabled = false;
            elements.threadDumpButton.textContent = '保存线程快照';
        }
    }

    function linuxCommands() {
        const pid = latest?.pid || '<PID>';
        return `PID=${pid}\n` +
            `top -p $PID\n` +
            `top -H -p $PID\n` +
            `printf '%x\\n' <高CPU线程TID>\n` +
            `jcmd $PID Thread.print -l > thread-$(date +%F-%H%M%S).txt\n` +
            `jstat -gcutil $PID 1000 30\n` +
            `jcmd $PID GC.heap_info\n` +
            `jcmd $PID GC.class_histogram\n` +
            `lsof -p $PID | wc -l\n` +
            `du -sh runtime/export-output runtime/tmp`;
    }

    async function copy(value, message) {
        await navigator.clipboard.writeText(value);
        showToast(message);
    }

    elements.mode.addEventListener('change', () => {
        if (elements.mode.value === 'FIXED') {
            elements.modeHelp.textContent = '查询为空后在外层执行 break，任务正常完成。';
        } else if (elements.mode.value === 'BUGGY_MISSING_BREAK') {
            elements.modeHelp.textContent = '数据结束后仍反复创建并关闭空 Workbook、执行末页查询；查询成本由下方策略决定。';
        }
    });
    elements.queryStrategy.addEventListener('change', () => {
        if (elements.queryStrategy.value === 'UNINDEXED_DEEP_OFFSET') {
            elements.queryHelp.textContent = 'customer_name 没有索引；每页都需要扫描和 filesort，OFFSET 越深丢弃的行越多。建议先提交1个任务，并取消保存 xlsx 以单独观察数据库影响。';
        } else {
            elements.queryHelp.textContent = '使用主键范围和 LIMIT，越往后查询成本基本稳定；数据结束后的空查询很轻。';
        }
    });
    elements.seedForm.addEventListener('submit', seed);
    elements.taskForm.addEventListener('submit', submitTask);
    elements.probeStart.addEventListener('click', startProbes);
    elements.probeStop.addEventListener('click', stopProbes);
    elements.probeBaseline.addEventListener('click', saveProbeBaseline);
    elements.probeReset.addEventListener('click', () => {
        resetProbeSeries();
        elements.probeMessage.textContent = '当前样本已清零；已经保存的基线保持不变。';
    });
    elements.threadDumpButton.addEventListener('click', captureThreadDump);
    elements.copyLinux.addEventListener('click', () => copy(linuxCommands(), 'Linux 诊断命令已复制'));
    elements.copyPid.addEventListener('click', () => copy(String(latest?.pid || ''), 'PID 已复制'));

    poll();
    setInterval(poll, 1000);
})();
