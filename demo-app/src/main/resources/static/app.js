(() => {
    const elements = {
        scenario: document.querySelector('#scenario'),
        scenarioHelp: document.querySelector('#scenario-help'),
        duration: document.querySelector('#duration'),
        intensity: document.querySelector('#intensity'),
        heap: document.querySelector('#heap-size'),
        chunk: document.querySelector('#chunk-size'),
        start: document.querySelector('#start-button'),
        stop: document.querySelector('#stop-button'),
        probe: document.querySelector('#probe-button'),
        probeResult: document.querySelector('#probe-result'),
        message: document.querySelector('#form-message'),
        dialog: document.querySelector('#confirm-dialog'),
        confirmStart: document.querySelector('#confirm-start'),
        confirmScenario: document.querySelector('#confirm-scenario'),
        confirmDuration: document.querySelector('#confirm-duration'),
        confirmHeap: document.querySelector('#confirm-heap'),
        serviceHealth: document.querySelector('#service-health'),
        status: document.querySelector('#status-badge'),
        probeRt: document.querySelector('#probe-rt'),
        cpu: document.querySelector('#cpu-percent'),
        heapPercent: document.querySelector('#heap-percent'),
        heapDetail: document.querySelector('#heap-detail'),
        gcCount: document.querySelector('#gc-count'),
        gcTime: document.querySelector('#gc-time'),
        liveThreads: document.querySelector('#live-threads'),
        poolState: document.querySelector('#pool-state'),
        heapLine: document.querySelector('#heap-line'),
        cpuLine: document.querySelector('#cpu-line'),
        chartTime: document.querySelector('#chart-time'),
        sampleSummary: document.querySelector('#sample-summary'),
        evidenceProgress: document.querySelector('#evidence-progress'),
        evidenceTitle: document.querySelector('#evidence-title'),
        evidenceAvailability: document.querySelector('#evidence-availability'),
        evidenceCommand: document.querySelector('#evidence-command'),
        evidenceSummary: document.querySelector('#evidence-summary'),
        evidenceContent: document.querySelector('#evidence-content code'),
        evidenceCaution: document.querySelector('#evidence-caution'),
        diagnosisOptions: document.querySelector('#diagnosis-options'),
        diagnose: document.querySelector('#diagnose-button'),
        diagnosisHelp: document.querySelector('#diagnosis-help'),
        diagnosisResult: document.querySelector('#diagnosis-result'),
        resultKicker: document.querySelector('#result-kicker'),
        resultTitle: document.querySelector('#result-title'),
        resultReasoning: document.querySelector('#result-reasoning'),
        resultEvidence: document.querySelector('#result-evidence'),
        resultActions: document.querySelector('#result-actions'),
        resultFixes: document.querySelector('#result-fixes'),
        toast: document.querySelector('#toast')
    };

    const scenarioCopy = {
        RANDOM: ['随机盲测', '系统会随机选择一种根因，提交判断前不会揭晓答案。'],
        HEAP_OOM: ['堆增长 / OOM', '对象被持续强引用保留，观察堆是否能在 GC 后回落。'],
        CPU_HIGH: ['CPU 计算热点', '计算线程持续占核，重点练习从进程 CPU 定位到线程栈。'],
        GC_THRASH: ['高频 GC', '大量短命对象快速产生，注意它和“存活对象泄漏”的区别。'],
        THREAD_POOL: ['线程池耗尽', '业务线程全部被占用、队列积压，但 CPU 和堆可能并不高。']
    };
    const terminalStatuses = new Set(['OOM', 'FAILED', 'CANCELLED', 'COMPLETED']);
    const liveEvidence = new Set(['THREAD_DUMP', 'HEAP', 'JVM_FLAGS']);
    let latestData = null;
    let collectedEvidence = new Set();
    let pollInFlight = false;
    let toastTimer;
    let diagnosisFinished = false;

    function currentConfig() {
        return {
            scenario: elements.scenario.value,
            durationSeconds: Number(elements.duration.value),
            intensity: Number(elements.intensity.value),
            heapMb: Number(elements.heap.value),
            chunkKb: Number(elements.chunk.value),
            intervalMs: 120
        };
    }

    function updateScenarioHelp() {
        elements.scenarioHelp.textContent = scenarioCopy[elements.scenario.value][1];
    }

    function showToast(message) {
        clearTimeout(toastTimer);
        elements.toast.textContent = message;
        elements.toast.classList.add('show');
        toastTimer = window.setTimeout(() => elements.toast.classList.remove('show'), 3400);
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 MB';
        const mb = bytes / 1024 / 1024;
        return `${mb >= 100 ? mb.toFixed(0) : mb.toFixed(1)} MB`;
    }

    function statusPresentation(status) {
        return {
            IDLE: ['idle', '等待现场'],
            STARTING: ['running', '创建中'],
            RUNNING: ['running', '现场存活'],
            OOM: ['critical', '子 JVM 已 OOM'],
            FAILED: ['failed', '实验失败'],
            CANCELLED: ['critical', '已重启 · 现场丢失'],
            COMPLETED: ['idle', '现场已自然结束']
        }[status] || ['idle', status];
    }

    function render(data) {
        latestData = data;
        collectedEvidence = new Set(data.collectedEvidence || []);
        const [statusClass, statusLabel] = statusPresentation(data.status);
        elements.status.className = `status-badge ${statusClass}`;
        elements.status.querySelector('strong').textContent = statusLabel;

        const active = data.status === 'STARTING' || data.status === 'RUNNING';
        const hasExperiment = Boolean(data.id);
        elements.start.disabled = active;
        elements.stop.disabled = !active;
        elements.probe.disabled = !active;
        elements.diagnosisOptions.disabled = !hasExperiment || diagnosisFinished;
        document.querySelectorAll('[data-evidence]').forEach(button => {
            button.disabled = !hasExperiment;
            button.classList.toggle('is-collected', collectedEvidence.has(button.dataset.evidence));
            if (!active && liveEvidence.has(button.dataset.evidence)) {
                button.title = '仍可点击，但会看到“进程退出后无法补采”的结果';
            } else {
                button.removeAttribute('title');
            }
        });

        const maxHeap = data.maxHeapBytes || data.heapLimitBytes || 0;
        const heapRatio = maxHeap > 0 ? Math.min(100, data.usedHeapBytes * 100 / maxHeap) : 0;
        elements.probeRt.textContent = hasExperiment ? `${data.probeRtMillis || 0} ms` : '—';
        elements.cpu.textContent = hasExperiment ? `${(data.cpuPercent || 0).toFixed(1)}%` : '—';
        elements.heapPercent.textContent = hasExperiment ? `${Math.round(heapRatio)}%` : '—';
        elements.heapDetail.textContent = `${formatBytes(data.usedHeapBytes)} / ${formatBytes(maxHeap)}`;
        elements.gcCount.textContent = hasExperiment ? String(data.gcCollections || 0) : '—';
        elements.gcTime.textContent = `${data.gcTimeMillis || 0} ms`;
        elements.liveThreads.textContent = hasExperiment ? String(data.liveThreads || 0) : '—';
        elements.poolState.textContent = hasExperiment ? `${data.poolActive || 0}/4 · Q${data.poolQueue || 0}` : '—';

        setMetricState('metric-rt', data.probeRtMillis >= 1000 ? 'alert' : data.probeRtMillis >= 250 ? 'warn' : '');
        setMetricState('metric-cpu', data.cpuPercent >= 80 ? 'alert' : data.cpuPercent >= 50 ? 'warn' : '');
        setMetricState('metric-heap', heapRatio >= 90 ? 'alert' : heapRatio >= 75 ? 'warn' : '');
        setMetricState('metric-pool', data.poolActive >= 4 && data.poolQueue > 0 ? 'alert' : '');
        setMetricState('metric-gc', gcRate(data.samples || []) >= 5 ? 'warn' : '');

        renderChart(data.samples || [], maxHeap);
        renderEvidenceProgress();
        updateDiagnosisAvailability();
        updateFlow(active, data);

        if (data.status === 'OOM') {
            elements.message.textContent = '子 JVM 已发生真实 OOM。进程退出后，现场型证据无法补采。';
        } else if (data.status === 'FAILED') {
            elements.message.textContent = data.errorMessage || '实验执行失败';
        } else if (active) {
            elements.message.textContent = '';
        }
    }

    function setMetricState(id, state) {
        const metric = document.querySelector(`#${id}`);
        metric.classList.remove('warn', 'alert');
        if (state) metric.classList.add(state);
    }

    function gcRate(samples) {
        if (samples.length < 2) return 0;
        const first = samples[Math.max(0, samples.length - 8)];
        const last = samples[samples.length - 1];
        const elapsed = Math.max(1, last.elapsedMillis - first.elapsedMillis);
        return (last.gcCollections - first.gcCollections) * 1000 / elapsed;
    }

    function renderChart(samples, maxHeap) {
        if (!samples.length || !maxHeap) {
            elements.heapLine.setAttribute('points', '0,240');
            elements.cpuLine.setAttribute('points', '0,240');
            elements.chartTime.textContent = '0.0 秒';
            elements.sampleSummary.textContent = '尚无采样数据。';
            return;
        }

        const maxElapsed = Math.max(1, samples[samples.length - 1].elapsedMillis);
        const toPoints = selector => samples.map(sample => {
            const x = sample.elapsedMillis * 900 / maxElapsed;
            const ratio = Math.min(1, Math.max(0, selector(sample)));
            const y = 240 - ratio * 220;
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        }).join(' ');
        elements.heapLine.setAttribute('points', toPoints(sample => sample.usedHeapBytes / maxHeap));
        elements.cpuLine.setAttribute('points', toPoints(sample => sample.cpuPercent / 200));
        elements.chartTime.textContent = `${(maxElapsed / 1000).toFixed(1)} 秒`;

        const last = samples[samples.length - 1];
        const heapRatio = Math.round(last.usedHeapBytes * 100 / maxHeap);
        elements.sampleSummary.textContent = `最近采样：CPU ${last.cpuPercent.toFixed(1)}%，堆 ${heapRatio}%，累计 GC ${last.gcCollections} 次，业务 RT ${last.probeRtMillis} ms，线程池 active ${last.poolActive}/4、queue ${last.poolQueue}。`;
    }

    function renderEvidenceProgress() {
        elements.evidenceProgress.textContent = `${collectedEvidence.size} / 6`;
        document.querySelectorAll('[data-evidence]').forEach(button => {
            button.classList.toggle('is-collected', collectedEvidence.has(button.dataset.evidence));
        });
    }

    function updateDiagnosisAvailability() {
        const selected = document.querySelector('input[name="cause"]:checked');
        const enoughEvidence = collectedEvidence.size >= 2;
        elements.diagnose.disabled = !latestData?.id || !selected || !enoughEvidence || diagnosisFinished;
        if (!latestData?.id) {
            elements.diagnosisHelp.textContent = '启动实验并至少采集两项证据后提交。';
        } else if (!enoughEvidence) {
            elements.diagnosisHelp.textContent = `还需采集 ${2 - collectedEvidence.size} 项证据。`;
        } else if (!selected) {
            elements.diagnosisHelp.textContent = '证据数量已满足，请选择一个根因假设。';
        } else if (!diagnosisFinished) {
            elements.diagnosisHelp.textContent = '可以提交。请确认你的判断能解释全部指标。';
        }
    }

    function updateFlow(active, data) {
        const steps = Array.from(document.querySelectorAll('.response-flow li'));
        steps.forEach(step => step.classList.remove('is-current'));
        let index = 0;
        if (data.id) index = collectedEvidence.size ? 2 : 1;
        if (diagnosisFinished) index = 4;
        else if (!active && terminalStatuses.has(data.status)) index = Math.max(index, 3);
        steps[Math.min(index, steps.length - 1)].classList.add('is-current');
    }

    function renderEvidence(evidence) {
        elements.evidenceTitle.textContent = `evidence://${evidence.type.toLowerCase()}`;
        elements.evidenceAvailability.className = `availability ${evidence.available ? 'available' : 'unavailable'}`;
        elements.evidenceAvailability.textContent = evidence.available ? '采集成功' : '不可补采';
        elements.evidenceCommand.textContent = `$ ${evidence.command}`;
        elements.evidenceSummary.textContent = evidence.summary;
        elements.evidenceContent.textContent = evidence.content;
        elements.evidenceCaution.hidden = !evidence.caution;
        elements.evidenceCaution.textContent = evidence.caution ? `注意：${evidence.caution}` : '';
    }

    function resetPracticeUi() {
        diagnosisFinished = false;
        collectedEvidence = new Set();
        document.querySelectorAll('input[name="cause"]').forEach(input => { input.checked = false; });
        elements.diagnosisResult.hidden = true;
        elements.evidenceTitle.textContent = 'evidence://waiting';
        elements.evidenceAvailability.className = 'availability';
        elements.evidenceAvailability.textContent = '等待采集';
        elements.evidenceCommand.textContent = '请选择上方工具';
        elements.evidenceSummary.textContent = '先形成假设，再找能够证伪或证实它的证据。';
        elements.evidenceContent.textContent = '现场证据将显示在这里。';
        elements.evidenceCaution.hidden = true;
        elements.probeResult.className = 'probe-result';
        elements.probeResult.textContent = '现场已创建，可以发起一次业务探测。';
        renderEvidenceProgress();
    }

    async function requestJson(url, options = {}) {
        const response = await fetch(url, {
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
            ...options
        });
        const payload = await response.json();
        if (!response.ok) throw new Error(payload.message || `请求失败 (${response.status})`);
        return payload;
    }

    async function startExperiment() {
        elements.start.disabled = true;
        elements.confirmStart.disabled = true;
        elements.message.textContent = '正在创建隔离子 JVM…';
        resetPracticeUi();
        try {
            const data = await requestJson('/api/lab/experiments', {
                method: 'POST',
                body: JSON.stringify(currentConfig())
            });
            render(data);
            showToast('故障现场已创建，请先观察再采证');
        } catch (error) {
            elements.message.textContent = error.message;
        } finally {
            elements.confirmStart.disabled = false;
            if (!latestData || !['STARTING', 'RUNNING'].includes(latestData.status)) elements.start.disabled = false;
        }
    }

    async function stopExperiment() {
        const confirmed = window.confirm('停止后将无法再采集线程快照、堆概览和 JVM 参数。确认模拟重启吗？');
        if (!confirmed) return;
        elements.stop.disabled = true;
        try {
            const data = await requestJson('/api/lab/experiments/current', { method: 'DELETE' });
            render(data);
            showToast('子 JVM 已停止，现场型证据已丢失');
        } catch (error) {
            elements.message.textContent = error.message;
        }
    }

    async function runProbe() {
        elements.probe.disabled = true;
        elements.probeResult.className = 'probe-result';
        elements.probeResult.textContent = '请求已发出，等待业务线程执行…';
        const startedAt = performance.now();
        try {
            const result = await requestJson('/api/lab/probe');
            const elapsed = Math.round(performance.now() - startedAt);
            const slow = result.result === 'TIMEOUT' || elapsed >= 1000;
            elements.probeResult.className = `probe-result${slow ? ' slow' : ''}`;
            elements.probeResult.textContent = `${result.result} · 浏览器实测 ${elapsed} ms · ${result.message}`;
        } catch (error) {
            elements.probeResult.className = 'probe-result slow';
            elements.probeResult.textContent = error.message;
        } finally {
            elements.probe.disabled = !latestData || !['STARTING', 'RUNNING'].includes(latestData.status);
        }
    }

    async function collect(type, button) {
        button.disabled = true;
        button.classList.add('is-loading');
        const original = button.querySelector('strong').textContent;
        button.querySelector('strong').textContent = '采集中…';
        try {
            const evidence = await requestJson(`/api/lab/evidence/${type}`);
            collectedEvidence.add(type);
            renderEvidence(evidence);
            renderEvidenceProgress();
            updateDiagnosisAvailability();
            showToast(evidence.available ? `${evidence.title}已采集` : `${evidence.title}已无法补采`);
        } catch (error) {
            showToast(error.message);
        } finally {
            button.querySelector('strong').textContent = original;
            button.classList.remove('is-loading');
            button.disabled = !latestData?.id;
        }
    }

    async function submitDiagnosis() {
        const selected = document.querySelector('input[name="cause"]:checked');
        if (!selected) return;
        elements.diagnose.disabled = true;
        try {
            const result = await requestJson('/api/lab/diagnoses', {
                method: 'POST',
                body: JSON.stringify({ cause: selected.value })
            });
            diagnosisFinished = true;
            renderDiagnosis(result);
            showToast(result.correct ? '判断正确，证据链成立' : '已揭晓根因，请对照证据复盘');
            await pollStatus();
        } catch (error) {
            elements.diagnosisHelp.textContent = error.message;
            elements.diagnose.disabled = false;
        }
    }

    function renderDiagnosis(result) {
        elements.diagnosisResult.hidden = false;
        elements.resultKicker.className = `result-kicker${result.correct ? '' : ' incorrect'}`;
        elements.resultKicker.textContent = result.correct ? 'DIAGNOSIS CONFIRMED' : 'DIAGNOSIS REVIEW';
        elements.resultTitle.textContent = result.title;
        elements.resultReasoning.textContent = result.reasoning;
        renderList(elements.resultEvidence, result.decisiveEvidence);
        renderList(elements.resultActions, result.immediateActions);
        renderList(elements.resultFixes, result.longTermFixes);
        elements.diagnosisOptions.disabled = true;
        elements.diagnose.disabled = true;
        elements.diagnosisHelp.textContent = `真实根因：${scenarioCopy[result.actualCause]?.[0] || result.actualCause}`;
        elements.diagnosisResult.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function renderList(element, items) {
        element.replaceChildren(...items.map(item => {
            const li = document.createElement('li');
            li.textContent = item;
            return li;
        }));
    }

    async function pollStatus() {
        if (pollInFlight) return;
        pollInFlight = true;
        try {
            const data = await requestJson('/api/lab/status');
            elements.serviceHealth.classList.remove('offline');
            elements.serviceHealth.querySelector('span:last-child').textContent = 'Spring Boot 在线';
            render(data);
        } catch (error) {
            elements.serviceHealth.classList.add('offline');
            elements.serviceHealth.querySelector('span:last-child').textContent = '服务离线';
            elements.status.className = 'status-badge failed';
            elements.status.querySelector('strong').textContent = '无法连接服务';
            elements.message.textContent = '无法连接 Spring Boot，请确认应用已启动。';
        } finally {
            pollInFlight = false;
        }
    }

    elements.scenario.addEventListener('change', updateScenarioHelp);
    elements.start.addEventListener('click', () => {
        const config = currentConfig();
        elements.confirmScenario.textContent = scenarioCopy[config.scenario][0];
        elements.confirmDuration.textContent = `${config.durationSeconds} 秒`;
        elements.confirmHeap.textContent = `${config.heapMb} MB`;
        elements.dialog.showModal();
    });
    elements.dialog.addEventListener('close', () => {
        if (elements.dialog.returnValue === 'confirm') startExperiment();
    });
    elements.stop.addEventListener('click', stopExperiment);
    elements.probe.addEventListener('click', runProbe);
    document.querySelectorAll('[data-evidence]').forEach(button => {
        button.addEventListener('click', () => collect(button.dataset.evidence, button));
        button.disabled = true;
    });
    document.querySelectorAll('input[name="cause"]').forEach(input => {
        input.addEventListener('change', updateDiagnosisAvailability);
    });
    elements.diagnose.addEventListener('click', submitDiagnosis);

    updateScenarioHelp();
    pollStatus();
    window.setInterval(pollStatus, 700);
})();
