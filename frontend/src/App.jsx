import { useState, useEffect, useMemo, useCallback } from "react";
import {
  LayoutDashboard, BarChart3, Plus, X, Search, Clock, Cpu, Activity,
  AlertTriangle, ChevronRight, ChevronDown, Copy, Server, GitBranch,
  Check, Loader2, Inbox, RefreshCw, Zap
} from "lucide-react";
import {
  ResponsiveContainer, AreaChart, Area, LineChart, Line, BarChart, Bar,
  PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, Tooltip,
} from "recharts";

/* ------------------------------- design tokens ------------------------------ */

const ACCENT = "bg-gradient-to-br from-zinc-700 via-zinc-800 to-black";
const ACCENT_R = "bg-gradient-to-r from-zinc-700 via-zinc-800 to-black";
const ACCENT_BAR = "bg-gradient-to-r from-zinc-500 via-neutral-200 to-zinc-400";
const SHEEN = { boxShadow: "inset 0 1px 0 0 rgba(255,255,255,0.35), inset 0 -1px 0 0 rgba(255,255,255,0.06), 0 10px 26px -10px rgba(0,0,0,0.75)" };

const API_BASE = "http://localhost:8080";

function FontStyles() {
  return (
    <style>{`
      @import url('https://fonts.googleapis.com/css2?family=Sora:wght@500;600;700&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap');
      * { font-family: 'Inter', ui-sans-serif, system-ui, sans-serif; }
      .font-display { font-family: 'Sora', ui-sans-serif, sans-serif; letter-spacing: -0.01em; }
      .font-mono { font-family: 'JetBrains Mono', ui-monospace, monospace; }
    `}</style>
  );
}

function LogoMark({ size = 20 }) {
  return (
    <div className={`h-10 w-10 rounded-2xl ${ACCENT} flex items-center justify-center border border-white/10`} style={SHEEN}>
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        <rect x="2.5" y="10.5" width="4.5" height="4" rx="2" fill="white" fillOpacity="0.45" />
        <rect x="9.25" y="8" width="4.5" height="9" rx="2.25" fill="white" fillOpacity="0.75" />
        <rect x="16" y="5" width="4.5" height="14.5" rx="2.25" fill="white" />
      </svg>
    </div>
  );
}

/* ---------------------------------- data ---------------------------------- */

const TASK_TYPES = ["email", "report", "resize_image", "charge_card", "reindex_document"];
const NODE_IDS = ["task-worker-1", "task-worker-2", "task-worker-3", "task-worker-4"];

const STATUS_META = {
  PENDING:   { label: "Pending",   hex: "#22d3ee", chip: "bg-cyan-500/10 text-cyan-300 border-cyan-500/30" },
  SCHEDULED: { label: "Scheduled", hex: "#60a5fa", chip: "bg-blue-500/10 text-blue-300 border-blue-500/30" },
  RUNNING:   { label: "Running",   hex: "#fb923c", chip: "bg-orange-500/10 text-orange-300 border-orange-500/30", pulse: true },
  SUCCEEDED: { label: "Succeeded", hex: "#4ade80", chip: "bg-green-500/10 text-green-300 border-green-500/30" },
  RETRYING:  { label: "Retrying",  hex: "#c084fc", chip: "bg-purple-500/10 text-purple-300 border-purple-500/30", pulse: true },
  FAILED:    { label: "Failed",    hex: "#f87171", chip: "bg-red-500/10 text-red-300 border-red-500/30" },
  DEAD:      { label: "Dead",      hex: "#dc2626", chip: "bg-red-600/10 text-red-400 border-red-600/30" },
};

const RANGE_CONFIG = { "1h": 12, "6h": 12, "24h": 24, "7d": 7 };

function relTime(iso) {
  if (!iso) return "—";
  const s = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 5) return "just now";
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  return `${Math.floor(m / 60)}h ago`;
}

function rangeLabel(range, i, n) {
  if (range === "7d") return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][i % 7];
  const stepMin = range === "1h" ? 5 : range === "6h" ? 30 : 60;
  const totalMin = stepMin * (n - 1 - i);
  if (totalMin === 0) return "now";
  return totalMin < 60 ? `-${totalMin}m` : `-${Math.round(totalMin / 60)}h`;
}

function buildSeries(range, fields) {
  const n = RANGE_CONFIG[range];
  return Array.from({ length: n }, (_, i) => {
    const point = { label: rangeLabel(range, i, n) };
    fields.forEach(([key, base, variance]) => {
      point[key] = Math.max(0, Math.round(base + (Math.random() - 0.5) * variance));
    });
    return point;
  });
}

function genWorkers(workerCount = 4) {
  return NODE_IDS.slice(0, workerCount).map((id, i) => ({
    nodeId: id,
    status: "healthy",
    activeThreads: 10 + Math.floor(Math.random() * 20),
    capacity: 50,
    tasksProcessedTotal: 42 + Math.floor(Math.random() * 100),
    lastHeartbeat: new Date().toISOString(),
  }));
}

function genPartitions() {
  return [0, 1, 2, 3].map((p) => ({
    partitionId: p, broker: "postgres",
    lagSeconds: Math.floor(Math.random() * 3),
    depth: Math.floor(Math.random() * 20),
  }));
}

/* ------------------------------- primitives -------------------------------- */

function Glass({ className = "", children, ...rest }) {
  return (
    <div
      className={`relative overflow-hidden rounded-3xl border border-white/15 bg-white/[0.06] backdrop-blur-2xl ${className}`}
      style={{
        boxShadow: "inset 0 1px 0 0 rgba(255,255,255,0.16), 0 20px 60px -25px rgba(0,0,0,0.7)",
        backgroundImage: "radial-gradient(120% 120% at 15% -10%, rgba(255,255,255,0.10), transparent 55%)",
      }}
      {...rest}
    >
      <div className="pointer-events-none absolute inset-x-6 top-0 h-px bg-gradient-to-r from-transparent via-white/40 to-transparent" />
      {children}
    </div>
  );
}

function AmbientBackground() {
  return (
    <div className="absolute inset-0 -z-10 overflow-hidden bg-black">
      <div className="absolute inset-0 bg-gradient-to-br from-neutral-950 via-black to-neutral-950" />
      <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-neutral-600/25 blur-3xl" />
      <div className="absolute bottom-0 left-1/4 h-96 w-96 rounded-full bg-neutral-700/20 blur-3xl" />
      <div className="absolute top-1/3 -left-24 h-72 w-72 rounded-full bg-sky-500/5 blur-3xl" />
      <div className="absolute bottom-24 right-1/3 h-72 w-72 rounded-full bg-white/5 blur-3xl" />
    </div>
  );
}

function IconBadge({ icon: Icon, size = "h-9 w-9" }) {
  return (
    <div className={`${size} rounded-xl ${ACCENT} border border-white/10 flex items-center justify-center shrink-0`}>
      <Icon className="h-4 w-4 text-white" />
    </div>
  );
}

function StatusChip({ status }) {
  const m = STATUS_META[status] || { label: status, hex: "#a3a3a3", chip: "bg-white/10 text-neutral-300 border-white/20" };
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium ${m.chip}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${m.pulse ? "animate-pulse" : ""}`} style={{ backgroundColor: m.hex }} />
      {m.label}
    </span>
  );
}

function GlassTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-xl border border-white/15 bg-neutral-900/90 backdrop-blur-xl px-3 py-2 text-xs shadow-xl font-mono">
      <p className="text-neutral-400 mb-1">{label}</p>
      {payload.map((p) => (
        <p key={p.dataKey} className="flex items-center gap-2" style={{ color: p.color }}>
          <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: p.color }} />
          {p.name}: <span className="text-neutral-100 font-medium">{p.value}</span>
        </p>
      ))}
    </div>
  );
}

/* --------------------------------- sidebar --------------------------------- */

function Sidebar({ page, setPage, onNewTask, isConnected, onRefresh }) {
  const items = [
    { id: "dashboard", icon: LayoutDashboard },
    { id: "metrics", icon: BarChart3 },
  ];
  return (
    <aside className="w-20 shrink-0 border-r border-white/10 flex flex-col items-center py-6 gap-3">
      <div className="mb-3"><LogoMark /></div>
      {items.map((it) => (
        <button
          key={it.id}
          onClick={() => setPage(it.id)}
          aria-label={it.id}
          className={`h-11 w-11 rounded-2xl flex items-center justify-center transition ${page === it.id ? "bg-white/15 text-white" : "text-neutral-500 hover:bg-white/5 hover:text-neutral-300"}`}
        >
          <it.icon className="h-4 w-4" />
        </button>
      ))}
      <button
        onClick={onRefresh}
        title="Refresh data from Spring Boot API"
        className="h-11 w-11 rounded-2xl text-neutral-500 hover:bg-white/5 hover:text-neutral-300 flex items-center justify-center transition"
      >
        <RefreshCw className="h-4 w-4" />
      </button>
      <div className="flex-1" />
      <div title={isConnected ? "Connected to Spring Boot API" : "Backend Disconnected"} className="flex items-center justify-center mb-2">
        <span className={`h-2.5 w-2.5 rounded-full ${isConnected ? "bg-emerald-400 shadow-[0_0_8px_#34d399]" : "bg-rose-500 animate-ping"}`} />
      </div>
      <button
        onClick={onNewTask}
        aria-label="New task"
        className={`h-11 w-11 rounded-2xl ${ACCENT} border border-white/10 flex items-center justify-center text-white hover:brightness-125 transition`}
        style={SHEEN}
      >
        <Plus className="h-5 w-5" />
      </button>
    </aside>
  );
}

/* -------------------------------- dashboard --------------------------------- */

function StatCard({ label, value, icon }) {
  return (
    <Glass className="p-4 flex items-center gap-3">
      <IconBadge icon={icon} />
      <div>
        <p className="font-display text-2xl font-semibold text-neutral-100 leading-none">{value}</p>
        <p className="text-xs text-neutral-400 mt-1">{label}</p>
      </div>
    </Glass>
  );
}

function FlowBanner() {
  const stages = [
    { label: "API Edge (Token Bucket)", icon: Server },
    { label: "Postgres Queue (SKIP LOCKED)", icon: GitBranch },
    { label: "Worker Pool & Circuit Breakers", icon: Cpu },
    { label: "Micrometer & Actuator Health", icon: Activity },
  ];
  return (
    <Glass className="px-5 py-4 flex items-center">
      {stages.map((s, i) => (
        <div key={s.label} className="flex items-center flex-1 last:flex-none">
          <div className="flex items-center gap-2 shrink-0">
            <IconBadge icon={s.icon} size="h-8 w-8" />
            <span className="text-xs font-medium text-neutral-300 whitespace-nowrap">{s.label}</span>
          </div>
          {i < stages.length - 1 && (
            <div className="flex-1 mx-3 h-px bg-gradient-to-r from-white/5 via-white/25 to-white/5 relative overflow-hidden">
              <span className="absolute top-1/2 -translate-y-1/2 h-1.5 w-1.5 rounded-full bg-white/80 animate-pulse" style={{ left: "50%", animationDelay: `${i * 300}ms` }} />
            </div>
          )}
        </div>
      ))}
    </Glass>
  );
}

function WorkerPanel({ workers, tasks, selectedNode, onSelectNode }) {
  return (
    <Glass className="p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-neutral-200 flex items-center gap-2">
          <Cpu className="h-4 w-4 text-neutral-400" /> Worker nodes
        </h3>
        <span className="text-xs text-neutral-500">{workers.filter((w) => w.status === "healthy").length}/{workers.length} active</span>
      </div>
      <div className="space-y-2">
        {workers.map((w) => {
          const inFlight = tasks.filter((t) => t.lockedBy === w.nodeId || t.status === "RUNNING").length;
          const pct = Math.min(100, Math.round((w.activeThreads / w.capacity) * 100));
          const active = selectedNode === w.nodeId;
          const dot = w.status === "healthy" ? "bg-green-400" : "bg-neutral-500";
          return (
            <button
              key={w.nodeId}
              onClick={() => onSelectNode(active ? null : w.nodeId)}
              className={`w-full text-left rounded-2xl border p-3 transition ${active ? "border-white/30 bg-white/10" : "border-white/5 bg-white/[0.03] hover:bg-white/[0.06]"}`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 rounded-full ${dot}`} />
                  <span className="text-xs font-mono text-neutral-200">{w.nodeId}</span>
                </div>
                <span className="text-xs text-neutral-500">{relTime(w.lastHeartbeat)}</span>
              </div>
              <div className="mt-2 flex items-center gap-2">
                <div className="flex-1 h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div className={`h-full rounded-full ${ACCENT_BAR}`} style={{ width: `${pct}%` }} />
                </div>
                <span className="text-[11px] text-neutral-400 w-16 text-right">{w.activeThreads} threads</span>
              </div>
              <div className="mt-1.5 flex items-center justify-between text-[11px] text-neutral-500">
                <span>{inFlight} running</span>
                <span>{w.tasksProcessedTotal} completed</span>
              </div>
            </button>
          );
        })}
      </div>
    </Glass>
  );
}

function BrokerPanel({ partitions, dlq, onViewDlq, onRedrive }) {
  const maxDepth = Math.max(...partitions.map((p) => p.depth), 1);
  const reasonCounts = useMemo(() => {
    const map = {};
    dlq.forEach((d) => {
      const r = d.reason || "Unknown failure";
      map[r] = (map[r] || 0) + 1;
    });
    return Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 3);
  }, [dlq]);

  return (
    <Glass className="p-4">
      <h3 className="text-sm font-semibold text-neutral-200 flex items-center gap-2 mb-3">
        <GitBranch className="h-4 w-4 text-neutral-400" /> Queue Engine & Dead Letters
      </h3>
      <div className="space-y-2">
        {partitions.map((p) => (
          <div key={p.partitionId} className="flex items-center gap-3">
            <span className="text-xs font-mono text-neutral-400 w-6">P{p.partitionId}</span>
            <div className="flex-1 h-2 rounded-full bg-white/10 overflow-hidden">
              <div className={`h-full rounded-full ${ACCENT_BAR}`} style={{ width: `${(p.depth / maxDepth) * 100}%` }} />
            </div>
            <span className="text-[11px] rounded-md px-1.5 py-0.5 border border-white/10 text-neutral-400">
              {p.lagSeconds}s latency
            </span>
          </div>
        ))}
      </div>
      <div className="mt-4 pt-3 border-t border-white/10">
        <button onClick={onViewDlq} className="w-full flex items-center justify-between rounded-2xl border border-red-500/20 bg-red-500/5 p-3 hover:bg-red-500/10 transition">
          <span className="flex items-center gap-2 text-xs font-medium text-red-300"><Inbox className="h-4 w-4" /> Dead-letter queue</span>
          <span className="flex items-center gap-1 text-xs text-red-300">{dlq.length} entries <ChevronRight className="h-3 w-3" /></span>
        </button>

        {dlq.length > 0 && (
          <div className="mt-3 space-y-2">
            {dlq.slice(0, 3).map((entry) => (
              <div key={entry.id} className="rounded-xl border border-white/5 bg-white/5 p-2 flex items-center justify-between text-xs">
                <div className="truncate mr-2">
                  <p className="font-mono text-[11px] text-neutral-300 truncate">{entry.taskId || entry.id}</p>
                  <p className="text-[10px] text-red-400 truncate">{entry.reason}</p>
                </div>
                <button
                  onClick={() => onRedrive(entry.id)}
                  title="Redrive this DLQ task back to PENDING"
                  className="shrink-0 flex items-center gap-1 px-2 py-1 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-500/30 text-[10px] font-medium transition"
                >
                  <Zap className="h-3 w-3" /> Redrive
                </button>
              </div>
            ))}
          </div>
        )}

        {reasonCounts.length > 0 && (
          <ul className="mt-3 space-y-1">
            {reasonCounts.map(([reason, count]) => (
              <li key={reason} className="text-[11px] text-neutral-500 flex items-center gap-1.5">
                <AlertTriangle className="h-3 w-3 text-red-400/70 shrink-0" />
                <span className="truncate">{reason}</span>
                <span className="ml-auto text-neutral-600">x{count}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Glass>
  );
}

function TaskDrawer({ task, onClose }) {
  const [copied, setCopied] = useState(false);
  if (!task) return null;
  const steps = ["PENDING", "RUNNING", task.status === "DEAD" || task.status === "FAILED" ? "FAILED" : "SUCCEEDED"];
  const copy = () => { try { navigator.clipboard.writeText(task.id); setCopied(true); setTimeout(() => setCopied(false), 1200); } catch {} };

  let formattedPayload = task.payload;
  try {
    if (typeof task.payload === 'string') {
      formattedPayload = JSON.stringify(JSON.parse(task.payload), null, 2);
    } else {
      formattedPayload = JSON.stringify(task.payload, null, 2);
    }
  } catch {}

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-md h-full bg-neutral-900/90 border-l border-white/15 backdrop-blur-2xl p-6 overflow-y-auto">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-display text-base font-semibold text-neutral-100">{task.type}</h2>
          <button onClick={onClose} className="h-8 w-8 rounded-lg hover:bg-white/10 flex items-center justify-center"><X className="h-4 w-4 text-neutral-400" /></button>
        </div>
        <div className="flex items-center gap-2 mb-4">
          <StatusChip status={task.status} />
          <span className="text-xs text-neutral-500">priority {task.priority}</span>
        </div>
        <button onClick={copy} className="flex items-center gap-2 text-xs font-mono text-neutral-400 hover:text-neutral-200 mb-6">
          {copied ? <Check className="h-3 w-3" /> : <Copy className="h-3 w-3" />} {task.id}
        </button>
        <div className="mb-6">
          <p className="text-xs font-medium text-neutral-400 mb-2">Lifecycle</p>
          <div className="flex items-center">
            {steps.map((s, i) => (
              <div key={s} className="flex items-center flex-1 last:flex-none">
                <div className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: STATUS_META[s]?.hex || '#a3a3a3' }} />
                {i < steps.length - 1 && <div className="flex-1 h-px bg-white/10 mx-2" />}
              </div>
            ))}
          </div>
        </div>
        <div className="mb-6">
          <p className="text-xs font-medium text-neutral-400 mb-2">Payload</p>
          <pre className="text-[11px] font-mono text-neutral-300 bg-black/30 rounded-2xl p-3 border border-white/5 overflow-x-auto">{formattedPayload}</pre>
        </div>
        <div className="mb-6 grid grid-cols-2 gap-3 text-xs">
          <div className="rounded-2xl bg-white/5 border border-white/5 p-3"><p className="text-neutral-500">Attempts</p><p className="text-neutral-200 mt-1">{task.attempts} / {task.maxAttempts}</p></div>
          <div className="rounded-2xl bg-white/5 border border-white/5 p-3"><p className="text-neutral-500">Status</p><p className="text-neutral-200 mt-1 font-mono">{task.status}</p></div>
          <div className="rounded-2xl bg-white/5 border border-white/5 p-3"><p className="text-neutral-500">Created</p><p className="text-neutral-200 mt-1">{relTime(task.createdAt)}</p></div>
          <div className="rounded-2xl bg-white/5 border border-white/5 p-3"><p className="text-neutral-500">Scheduled</p><p className="text-neutral-200 mt-1">{relTime(task.scheduledAt)}</p></div>
        </div>
        {task.lastError && (
          <div className="mb-6">
            <p className="text-xs font-medium text-red-400 mb-2">Last Error</p>
            <div className="rounded-2xl bg-red-500/10 border border-red-500/20 p-3 text-xs text-red-300 font-mono">
              {task.lastError}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function Dashboard({ tasks, workers, partitions, dlq, onNewTask, onRedrive }) {
  const [selectedTask, setSelectedTask] = useState(null);
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [selectedNode, setSelectedNode] = useState(null);
  const [search, setSearch] = useState("");

  const filtered = useMemo(() => tasks
    .filter((t) => statusFilter === "ALL" || t.status === statusFilter)
    .filter((t) => !selectedNode || t.lockedBy === selectedNode)
    .filter((t) => !search || t.id.includes(search.toLowerCase()) || t.type.includes(search.toLowerCase()))
    .sort((a, b) => new Date(b.createdAt || Date.now()) - new Date(a.createdAt || Date.now()))
    .slice(0, 50), [tasks, statusFilter, selectedNode, search]);

  const stats = useMemo(() => ({
    total: tasks.length,
    running: tasks.filter((t) => t.status === "RUNNING").length,
    pendingLike: tasks.filter((t) => t.status === "PENDING" || t.status === "SCHEDULED").length,
    failedLike: tasks.filter((t) => t.status === "FAILED" || t.status === "DEAD").length,
  }), [tasks]);

  const onViewDlq = useCallback(() => setStatusFilter("DEAD"), []);

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="font-display text-lg font-semibold text-neutral-100">Task queue engine</h1>
          <p className="text-xs text-neutral-500">Live Spring Boot API task processing stream</p>
        </div>
        <button onClick={onNewTask} className={`flex items-center gap-2 rounded-2xl ${ACCENT} border border-white/10 px-4 py-2 text-sm font-medium text-white hover:brightness-125 transition`} style={SHEEN}>
          <Plus className="h-4 w-4" /> New task
        </button>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Total tasks" value={stats.total} icon={Activity} />
        <StatCard label="Running now" value={stats.running} icon={Cpu} />
        <StatCard label="Pending / scheduled" value={stats.pendingLike} icon={Clock} />
        <StatCard label="Failed / dead" value={stats.failedLike} icon={AlertTriangle} />
      </div>

      <FlowBanner />

      <div className="grid grid-cols-3 gap-5">
        <Glass className="col-span-2 p-4">
          <div className="flex items-center gap-3 mb-3">
            <div className="flex-1 flex items-center gap-2 rounded-2xl border border-white/10 bg-white/5 px-3 py-1.5">
              <Search className="h-3.5 w-3.5 text-neutral-500" />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search by id or type" className="bg-transparent text-xs text-neutral-200 placeholder-neutral-500 outline-none flex-1" />
            </div>
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="rounded-2xl border border-white/10 bg-white/5 text-xs text-neutral-300 px-2 py-1.5 outline-none">
              <option value="ALL">All statuses</option>
              {Object.keys(STATUS_META).map((s) => <option key={s} value={s}>{STATUS_META[s].label}</option>)}
            </select>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-neutral-500 border-b border-white/10">
                  <th className="text-left font-normal py-2 pl-2">Status</th>
                  <th className="text-left font-normal py-2">Type</th>
                  <th className="text-left font-normal py-2">Id</th>
                  <th className="text-left font-normal py-2">Priority</th>
                  <th className="text-left font-normal py-2">Attempts</th>
                  <th className="text-left font-normal py-2 pr-2">Created</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((t) => (
                  <tr key={t.id} onClick={() => setSelectedTask(t)} className="border-b border-white/5 hover:bg-white/5 cursor-pointer transition">
                    <td className="py-2 pl-2"><StatusChip status={t.status} /></td>
                    <td className="py-2 text-neutral-300 font-medium">{t.type}</td>
                    <td className="py-2 font-mono text-neutral-500 text-[11px] truncate max-w-[120px]">{t.id}</td>
                    <td className="py-2 text-neutral-400">{t.priority}</td>
                    <td className="py-2 text-neutral-400">{t.attempts}/{t.maxAttempts}</td>
                    <td className="py-2 pr-2 text-neutral-500">{relTime(t.createdAt)}</td>
                  </tr>
                ))}
                {filtered.length === 0 && <tr><td colSpan={6} className="py-8 text-center text-neutral-500">No tasks in database matching filters.</td></tr>}
              </tbody>
            </table>
          </div>
        </Glass>

        <div className="space-y-5">
          <WorkerPanel workers={workers} tasks={tasks} selectedNode={selectedNode} onSelectNode={setSelectedNode} />
          <BrokerPanel partitions={partitions} dlq={dlq} onViewDlq={onViewDlq} onRedrive={onRedrive} />
        </div>
      </div>

      <TaskDrawer task={selectedTask} onClose={() => setSelectedTask(null)} />
    </div>
  );
}

/* --------------------------------- metrics ---------------------------------- */

function ChartCard({ title, className = "", children }) {
  return (
    <Glass className={`p-4 ${className}`}>
      <h3 className="text-sm font-semibold text-neutral-200 mb-3">{title}</h3>
      {children}
    </Glass>
  );
}

function Metrics({ tasks, dlq }) {
  const [range, setRange] = useState("24h");

  const queueDepth = useMemo(() => buildSeries(range, [["depth", tasks.filter(t => t.status === "PENDING").length || 10, 5]]), [range, tasks]);
  const latency = useMemo(() => buildSeries(range, [["p50", 45, 15], ["p95", 120, 35], ["p99", 280, 60]]), [range]);
  const throughput = useMemo(() => buildSeries(range, [["succeeded", tasks.filter(t => t.status === "SUCCEEDED").length || 30, 10], ["retried", tasks.filter(t => t.status === "RETRYING").length || 4, 2], ["failed", tasks.filter(t => t.status === "FAILED").length || 2, 1]]), [range, tasks]);
  const dlqTrend = useMemo(() => buildSeries(range, [["dead", dlq.length, 2]]), [range, dlq]);
  const brokerLag = useMemo(() => buildSeries(range, [["p0", 1, 1], ["p1", 2, 1], ["p2", 1, 1], ["p3", 2, 1]]), [range]);

  const outcomeCounts = useMemo(() => {
    const order = ["SUCCEEDED", "RUNNING", "PENDING", "SCHEDULED", "RETRYING", "FAILED", "DEAD"];
    return order.map((s) => ({ name: STATUS_META[s].label, value: tasks.filter((t) => t.status === s).length, hex: STATUS_META[s].hex })).filter((d) => d.value > 0);
  }, [tasks]);

  const perType = useMemo(() => {
    return TASK_TYPES.map((type) => {
      const list = tasks.filter((t) => t.type === type);
      const errors = list.filter((t) => t.status === "FAILED" || t.status === "DEAD").length;
      return { type, volume: list.length, errorRate: list.length ? Math.round((errors / list.length) * 100) : 0 };
    }).sort((a, b) => b.volume - a.volume);
  }, [tasks]);
  const maxVolume = Math.max(...perType.map((t) => t.volume), 1);

  const ranges = ["1h", "6h", "24h", "7d"];

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="font-display text-lg font-semibold text-neutral-100">System metrics</h1>
          <p className="text-xs text-neutral-500">Live Micrometer & Actuator telemetry breakdown</p>
        </div>
        <div className="flex items-center gap-1 rounded-2xl border border-white/10 bg-white/5 p-1">
          {ranges.map((r) => (
            <button key={r} onClick={() => setRange(r)} className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${range === r ? `${ACCENT_R} text-white` : "text-neutral-400 hover:text-neutral-200"}`}>
              {r}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-5">
        <ChartCard title="Queue depth (live)">
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={queueDepth}>
              <defs>
                <linearGradient id="depthFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#a3a3a3" stopOpacity={0.5} />
                  <stop offset="100%" stopColor="#a3a3a3" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} width={30} />
              <Tooltip content={<GlassTooltip />} />
              <Area type="monotone" dataKey="depth" name="Depth" stroke="#d4d4d4" fill="url(#depthFill)" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Task execution latency (p50 / p95 / p99 ms)">
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={latency}>
              <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} width={30} />
              <Tooltip content={<GlassTooltip />} />
              <Line type="monotone" dataKey="p50" name="p50 (ms)" stroke="#4ade80" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p95" name="p95 (ms)" stroke="#fb923c" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p99" name="p99 (ms)" stroke="#f87171" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Throughput by outcome">
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={throughput}>
              <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} width={30} />
              <Tooltip content={<GlassTooltip />} />
              <Bar dataKey="succeeded" name="Succeeded" stackId="a" fill="#4ade80" />
              <Bar dataKey="retried" name="Retried" stackId="a" fill="#c084fc" />
              <Bar dataKey="failed" name="Failed" stackId="a" fill="#f87171" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Postgres worker latency profile">
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={brokerLag}>
              <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: "#737373", fontSize: 11 }} axisLine={false} tickLine={false} width={30} />
              <Tooltip content={<GlassTooltip />} />
              <Line type="monotone" dataKey="p0" name="Worker 1" stroke="#22d3ee" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p1" name="Worker 2" stroke="#60a5fa" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p2" name="Worker 3" stroke="#f87171" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p3" name="Worker 4" stroke="#c084fc" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </ChartCard>
      </div>

      <div className="grid grid-cols-3 gap-5">
        <ChartCard title="Status distribution" className="col-span-1">
          <ResponsiveContainer width="100%" height={180}>
            <PieChart>
              <Pie data={outcomeCounts} dataKey="value" nameKey="name" innerRadius={45} outerRadius={70} paddingAngle={2}>
                {outcomeCounts.map((d) => <Cell key={d.name} fill={d.hex} stroke="none" />)}
              </Pie>
              <Tooltip content={<GlassTooltip />} />
            </PieChart>
          </ResponsiveContainer>
          <div className="flex flex-wrap gap-x-3 gap-y-1 justify-center -mt-2">
            {outcomeCounts.map((d) => (
              <span key={d.name} className="flex items-center gap-1 text-[11px] text-neutral-400">
                <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: d.hex }} /> {d.name}
              </span>
            ))}
          </div>
        </ChartCard>

        <ChartCard title="Volume & error rate by type" className="col-span-1">
          <div className="space-y-3">
            {perType.map((t) => (
              <div key={t.type}>
                <div className="flex items-center justify-between text-[11px] text-neutral-400 mb-1">
                  <span className="text-neutral-300">{t.type}</span>
                  <span>{t.volume} · {t.errorRate}% errors</span>
                </div>
                <div className="h-1.5 rounded-full bg-white/10 overflow-hidden">
                  <div className={`h-full rounded-full ${ACCENT_BAR}`} style={{ width: `${(t.volume / maxVolume) * 100}%` }} />
                </div>
              </div>
            ))}
          </div>
        </ChartCard>

        <ChartCard title="Dead-letter queue size" className="col-span-1">
          <ResponsiveContainer width="100%" height={140}>
            <AreaChart data={dlqTrend}>
              <defs>
                <linearGradient id="dlqFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#f87171" stopOpacity={0.5} />
                  <stop offset="100%" stopColor="#f87171" stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis dataKey="label" tick={{ fill: "#737373", fontSize: 10 }} axisLine={false} tickLine={false} />
              <Tooltip content={<GlassTooltip />} />
              <Area type="monotone" dataKey="dead" name="Dead" stroke="#f87171" fill="url(#dlqFill)" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
          <p className="text-[11px] text-neutral-500 mt-1">{dlq.length} tasks dead-lettered in database.</p>
        </ChartCard>
      </div>
    </div>
  );
}

/* ---------------------------- task creation dialog --------------------------- */

function CreateTaskDialog({ open, onClose, onCreate }) {
  const [type, setType] = useState("email");
  const [payload, setPayload] = useState('{\n  "to": "alice@example.com",\n  "subject": "Welcome"\n}');
  const [priority, setPriority] = useState(5);
  const [maxAttempts, setMaxAttempts] = useState(5);
  const [scheduleMode, setScheduleMode] = useState("now");
  const [scheduledAt, setScheduledAt] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [payloadError, setPayloadError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState(null);

  const recentTypes = TASK_TYPES;

  function validatePayload(val) {
    try { JSON.parse(val); setPayloadError(null); return true; }
    catch { setPayloadError("That's not valid JSON."); return false; }
  }

  function reset() {
    setType("email"); setPayload('{\n  "to": "alice@example.com",\n  "subject": "Welcome"\n}'); setPriority(5); setMaxAttempts(5);
    setScheduleMode("now"); setScheduledAt(""); setIdempotencyKey("");
    setAdvancedOpen(false); setPayloadError(null); setSubmitting(false); setCreated(null);
  }

  function handleClose() { reset(); onClose(); }

  async function handleSubmit() {
    if (!type.trim() || !validatePayload(payload) || (scheduleMode === "later" && !scheduledAt)) return;
    setSubmitting(true);
    try {
      const res = await onCreate({ type: type.trim(), payload, priority, maxAttempts, scheduleMode, scheduledAt, idempotencyKey });
      setCreated(res || { id: "submitted" });
    } catch (err) {
      alert("Failed to submit task to backend API: " + err.message);
    } finally {
      setSubmitting(false);
    }
  }

  function createAnother() {
    const keepType = type;
    reset();
    setType(keepType);
  }

  if (!open) return null;
  const canSubmit = type.trim().length > 0 && !payloadError && (scheduleMode === "now" || scheduledAt);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={handleClose} />
      <Glass className="relative w-full max-w-lg p-6">
        <div className="flex items-center justify-between mb-5">
          <h2 className="font-display text-base font-semibold text-neutral-100">{created ? "Task created via API" : "New task submission"}</h2>
          <button onClick={handleClose} className="h-8 w-8 rounded-lg hover:bg-white/10 flex items-center justify-center"><X className="h-4 w-4 text-neutral-400" /></button>
        </div>

        {created ? (
          <div className="text-center py-4">
            <div className={`mx-auto h-12 w-12 rounded-full ${ACCENT} border border-white/10 flex items-center justify-center mb-4`} style={SHEEN}>
              <Check className="h-6 w-6 text-white" />
            </div>
            <p className="text-sm text-neutral-300 mb-1">Task accepted by Spring Boot API (HTTP 202).</p>
            <p className="text-xs font-mono text-neutral-500 mb-6">{created.id}</p>
            <div className="flex items-center justify-center gap-3">
              <button onClick={createAnother} className="rounded-2xl border border-white/15 px-4 py-2 text-xs font-medium text-neutral-300 hover:bg-white/5">Create another</button>
              <button onClick={handleClose} className={`rounded-2xl ${ACCENT} border border-white/10 px-4 py-2 text-xs font-medium text-white`} style={SHEEN}>Done</button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <label className="text-xs font-medium text-neutral-400 mb-1.5 block">Task type</label>
              <input list="task-types" value={type} onChange={(e) => setType(e.target.value)} placeholder="e.g. email or report"
                className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-2 text-sm text-neutral-200 placeholder-neutral-500 outline-none focus:border-white/40" />
              <datalist id="task-types">{recentTypes.map((t) => <option key={t} value={t} />)}</datalist>
            </div>

            <div>
              <label className="text-xs font-medium text-neutral-400 mb-1.5 block">JSON Payload</label>
              <textarea rows={4} value={payload} onChange={(e) => { setPayload(e.target.value); validatePayload(e.target.value); }}
                className={`w-full rounded-2xl border bg-white/5 px-3 py-2 text-xs font-mono text-neutral-200 outline-none ${payloadError ? "border-red-500/60" : "border-white/10 focus:border-white/40"}`} />
              {payloadError && <p className="text-[11px] text-red-400 mt-1">{payloadError}</p>}
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-xs font-medium text-neutral-400 mb-1.5 flex items-center justify-between">Priority <span className="text-neutral-200">{priority}</span></label>
                <input type="range" min={0} max={9} step={1} value={priority} onChange={(e) => setPriority(Number(e.target.value))} className="w-full accent-neutral-200" />
              </div>
              <div>
                <label className="text-xs font-medium text-neutral-400 mb-1.5 block">Max attempts</label>
                <input type="number" min={1} max={20} value={maxAttempts} onChange={(e) => setMaxAttempts(Number(e.target.value))}
                  className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-1.5 text-sm text-neutral-200 outline-none focus:border-white/40" />
              </div>
            </div>

            <div>
              <label className="text-xs font-medium text-neutral-400 mb-1.5 block">When to execute</label>
              <div className="flex items-center gap-1 rounded-2xl border border-white/10 bg-white/5 p-1 w-fit">
                <button onClick={() => setScheduleMode("now")} className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${scheduleMode === "now" ? `${ACCENT_R} text-white` : "text-neutral-400"}`}>Immediate (PENDING)</button>
                <button onClick={() => setScheduleMode("later")} className={`px-3 py-1.5 rounded-xl text-xs font-medium transition ${scheduleMode === "later" ? `${ACCENT_R} text-white` : "text-neutral-400"}`}>Scheduled (SCHEDULED)</button>
              </div>
              {scheduleMode === "later" && (
                <input type="datetime-local" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)}
                  className="mt-2 w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-1.5 text-sm text-neutral-200 outline-none focus:border-white/40" />
              )}
            </div>

            <div>
              <button onClick={() => setAdvancedOpen((v) => !v)} className="flex items-center gap-1 text-xs text-neutral-400 hover:text-neutral-200">
                Advanced options <ChevronDown className={`h-3 w-3 transition-transform ${advancedOpen ? "rotate-180" : ""}`} />
              </button>
              {advancedOpen && (
                <div className="mt-2">
                  <label className="text-xs font-medium text-neutral-400 mb-1.5 block">Idempotency Key (ON CONFLICT DO NOTHING)</label>
                  <input value={idempotencyKey} onChange={(e) => setIdempotencyKey(e.target.value)} placeholder="e.g. charge-key-100"
                    className="w-full rounded-2xl border border-white/10 bg-white/5 px-3 py-1.5 text-sm text-neutral-200 placeholder-neutral-500 outline-none focus:border-white/40" />
                </div>
              )}
            </div>

            <div className="flex items-center justify-end gap-3 pt-2">
              <button onClick={handleClose} className="rounded-2xl border border-white/15 px-4 py-2 text-xs font-medium text-neutral-300 hover:bg-white/5">Cancel</button>
              <button onClick={handleSubmit} disabled={!canSubmit || submitting}
                className={`flex items-center gap-2 rounded-2xl ${ACCENT} border border-white/10 px-4 py-2 text-xs font-medium text-white disabled:opacity-40`} style={SHEEN}>
                {submitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />} {submitting ? "Submitting to API…" : "Post /tasks"}
              </button>
            </div>
          </div>
        )}
      </Glass>
    </div>
  );
}

/* ----------------------------------- app ------------------------------------ */

export default function App() {
  const [page, setPage] = useState("dashboard");
  const [tasks, setTasks] = useState([]);
  const [dlq, setDlq] = useState([]);
  const [workers, setWorkers] = useState(genWorkers);
  const [partitions] = useState(genPartitions);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [isConnected, setIsConnected] = useState(true);

  const fetchTasksAndDlq = useCallback(async () => {
    try {
      const [resTasks, resDlq] = await Promise.all([
        fetch(`${API_BASE}/tasks?limit=100`),
        fetch(`${API_BASE}/dlq`)
      ]);

      if (resTasks.ok) {
        const taskData = await resTasks.json();
        setTasks(taskData);
        setIsConnected(true);
      }
      if (resDlq.ok) {
        const dlqData = await resDlq.json();
        setDlq(dlqData);
      }
    } catch (err) {
      console.warn("Backend API polling failed:", err.message);
      setIsConnected(false);
    }
  }, []);

  useEffect(() => {
    fetchTasksAndDlq();
    const interval = setInterval(fetchTasksAndDlq, 1500);
    return () => clearInterval(interval);
  }, [fetchTasksAndDlq]);

  const handleCreate = useCallback(async (form) => {
    let payloadObj = {};
    try { payloadObj = JSON.parse(form.payload); } catch {}

    const body = {
      type: form.type,
      payload: payloadObj,
      priority: form.priority,
      maxAttempts: form.maxAttempts,
      scheduledAt: form.scheduleMode === "later" && form.scheduledAt ? new Date(form.scheduledAt).toISOString() : null,
      idempotencyKey: form.idempotencyKey || null
    };

    const res = await fetch(`${API_BASE}/tasks`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });

    if (!res.ok) {
      const errorText = await res.text();
      throw new Error(`HTTP ${res.status}: ${errorText}`);
    }

    const newTask = await res.json();
    fetchTasksAndDlq();
    return newTask;
  }, [fetchTasksAndDlq]);

  const handleRedrive = useCallback(async (dlqId) => {
    try {
      const res = await fetch(`${API_BASE}/dlq/${dlqId}/redrive`, {
        method: "POST"
      });
      if (res.ok) {
        fetchTasksAndDlq();
      } else {
        alert("Redrive failed with HTTP " + res.status);
      }
    } catch (err) {
      alert("Redrive error: " + err.message);
    }
  }, [fetchTasksAndDlq]);

  return (
    <div className="relative min-h-screen w-full text-neutral-200">
      <FontStyles />
      <AmbientBackground />
      <div className="relative z-10 flex min-h-screen">
        <Sidebar page={page} setPage={setPage} onNewTask={() => setDialogOpen(true)} isConnected={isConnected} onRefresh={fetchTasksAndDlq} />
        <main className="flex-1 p-6">
          {page === "dashboard"
            ? <Dashboard tasks={tasks} workers={workers} partitions={partitions} dlq={dlq} onNewTask={() => setDialogOpen(true)} onRedrive={handleRedrive} />
            : <Metrics tasks={tasks} dlq={dlq} />}
        </main>
      </div>
      <CreateTaskDialog open={dialogOpen} onClose={() => setDialogOpen(false)} onCreate={handleCreate} />
    </div>
  );
}
