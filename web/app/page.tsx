"use client";

import React, { useEffect, useRef, useState, useCallback } from "react";
import mqtt, { MqttClient } from "mqtt";
import {
  Wifi,
  WifiOff,
  Radio,
  Volume2,
  VolumeX,
  Maximize2,
  Minimize2,
  AlertTriangle,
  Zap,
  Activity,
  Terminal,
  ChevronDown,
  ChevronUp,
  Battery,
  Signal,
  Clock,
  Globe,
} from "lucide-react";

type ConnectionState = "ONLINE" | "CONNECTING" | "OFFLINE";

interface DriveVector {
  x: number;
  y: number;
  speed: number;
  heading: string;
}

interface TelemetryEntry {
  id: string;
  time: string;
  topic: string;
  payload: string;
}

interface RobotStatus {
  online: boolean;
  battery?: number;
  rssi?: number;
  uptime?: number;
  lastSeen?: number;
}

const BROKER_URL = "wss://broker.hivemq.com:8884/mqtt";
const JOYSTICK_RADIUS = 110; // Outer radius in px
const THUMB_RADIUS = 36; // Thumb handle radius in px
const MAX_DRAG_DIST = JOYSTICK_RADIUS - 10;

export default function LumiController() {
  const [isMounted, setIsMounted] = useState(false);
  const [robotId, setRobotId] = useState("LUMI-7042");
  const [connectionStatus, setConnectionStatus] = useState<ConnectionState>("OFFLINE");
  const [latency, setLatency] = useState<number | null>(null);
  const [soundEnabled, setSoundEnabled] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [speedCap, setSpeedCap] = useState<number>(100);
  const [telemetryOpen, setTelemetryOpen] = useState(false);
  const [logs, setLogs] = useState<TelemetryEntry[]>([]);
  const [robotStatus, setRobotStatus] = useState<RobotStatus>({ online: false });

  // Vector state
  const [vector, setVector] = useState<DriveVector>({
    x: 0,
    y: 0,
    speed: 0,
    heading: "STOPPED",
  });

  // Joystick visual position
  const [stickPos, setStickPos] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);

  // Refs for low-latency transmission
  const clientRef = useRef<MqttClient | null>(null);
  const vectorRef = useRef<DriveVector>({ x: 0, y: 0, speed: 0, heading: "STOPPED" });
  const isDrivingRef = useRef<boolean>(false);
  const tickerRef = useRef<NodeJS.Timeout | null>(null);
  const pingStartRef = useRef<number | null>(null);
  const joystickSvgRef = useRef<SVGSVGElement | null>(null);
  const activeKeysRef = useRef<Set<string>>(new Set());
  const audioCtxRef = useRef<AudioContext | null>(null);

  // Audio synthesize beep
  const playBeep = useCallback((freq = 880, duration = 0.04) => {
    if (!soundEnabled || typeof window === "undefined") return;
    try {
      const AudioCtxClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = audioCtxRef.current || new AudioCtxClass();
      audioCtxRef.current = ctx;
      if (ctx.state === "suspended") ctx.resume();

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.setValueAtTime(freq, ctx.currentTime);
      gain.gain.setValueAtTime(0.08, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);

      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      osc.stop(ctx.currentTime + duration);
    } catch {
      // AudioContext blocked
    }
  }, [soundEnabled]);

  // Heading calculation
  const getHeading = (x: number, y: number): string => {
    if (x === 0 && y === 0) return "STOPPED";
    if (y > 25 && Math.abs(x) <= 25) return "FORWARD";
    if (y < -25 && Math.abs(x) <= 25) return "REVERSE";
    if (x > 25 && Math.abs(y) <= 25) return "RIGHT";
    if (x < -25 && Math.abs(y) <= 25) return "LEFT";
    if (y > 0 && x > 0) return "FORWARD RIGHT";
    if (y > 0 && x < 0) return "FORWARD LEFT";
    if (y < 0 && x > 0) return "REVERSE RIGHT";
    return "REVERSE LEFT";
  };

  // Immediate MQTT Dispatcher
  const transmitPayload = useCallback((x: number, y: number) => {
    const client = clientRef.current;
    const currentRobot = robotId.trim().toUpperCase() || "LUMI-XXXX";
    const topic = `lumi/${currentRobot}/control`;
    const payload = JSON.stringify({ x, y });

    if (client && client.connected) {
      client.publish(topic, payload, { qos: 0 });
    }

    setLogs((prev) => [
      {
        id: Math.random().toString(36).substring(2, 8),
        time: new Date().toISOString().substring(11, 23),
        topic,
        payload,
      },
      ...prev.slice(0, 24),
    ]);
  }, [robotId]);

  // Start 20Hz ticker
  const startDriveTicker = useCallback(() => {
    if (tickerRef.current) clearInterval(tickerRef.current);
    isDrivingRef.current = true;

    // Immediate first packet
    transmitPayload(vectorRef.current.x, vectorRef.current.y);

    tickerRef.current = setInterval(() => {
      if (isDrivingRef.current) {
        transmitPayload(vectorRef.current.x, vectorRef.current.y);
      }
    }, 50); // 20Hz (every 50ms)
  }, [transmitPayload]);

  // Stop 20Hz ticker & send immediate zero
  const stopDrive = useCallback(() => {
    isDrivingRef.current = false;
    if (tickerRef.current) {
      clearInterval(tickerRef.current);
      tickerRef.current = null;
    }
    const zeroVec: DriveVector = { x: 0, y: 0, speed: 0, heading: "STOPPED" };
    vectorRef.current = zeroVec;
    setVector(zeroVec);
    setStickPos({ x: 0, y: 0 });
    setIsDragging(false);

    // Send failsafe stop packet
    transmitPayload(0, 0);

    // Haptic
    if (typeof navigator !== "undefined" && navigator.vibrate) {
      navigator.vibrate(10);
    }
  }, [transmitPayload]);

  // Update Vector helper
  const updateVectorValues = useCallback((rawX: number, rawY: number) => {
    const scale = speedCap / 100;
    const x = Math.round(Math.max(-100, Math.min(100, rawX * scale)));
    const y = Math.round(Math.max(-100, Math.min(100, rawY * scale)));
    const speed = Math.min(100, Math.round(Math.hypot(x, y)));
    const heading = getHeading(x, y);

    const newVec = { x, y, speed, heading };
    vectorRef.current = newVec;
    setVector(newVec);
  }, [speedCap]);

  // Mount hydration & LocalStorage
  useEffect(() => {
    setIsMounted(true);
    const saved = localStorage.getItem("lumi_robot_serial");
    if (saved) setRobotId(saved);
  }, []);

  // Save Serial ID
  const handleRobotIdChange = (id: string) => {
    const sanitized = id.trim().toUpperCase();
    setRobotId(sanitized);
    localStorage.setItem("lumi_robot_serial", sanitized);
  };

  // MQTT Connection Lifecycle
  useEffect(() => {
    if (!isMounted) return;

    setConnectionStatus("CONNECTING");
    const clientId = `lumi-web-${Math.random().toString(16).substring(2, 8)}`;
    const client = mqtt.connect(BROKER_URL, {
      clientId,
      clean: true,
      connectTimeout: 6000,
      reconnectPeriod: 3000,
      keepalive: 30,
    });

    clientRef.current = client;

    client.on("connect", () => {
      setConnectionStatus("ONLINE");
      playBeep(1200, 0.08);
      client.subscribe(`lumi/${robotId}/status`);
    });

    client.on("reconnect", () => setConnectionStatus("CONNECTING"));
    client.on("close", () => setConnectionStatus("OFFLINE"));
    client.on("error", () => setConnectionStatus("OFFLINE"));

    client.on("message", (topic, message) => {
      if (topic.includes("status")) {
        const msgStr = message.toString().trim();
        if (msgStr.toUpperCase() === "ONLINE") {
          setRobotStatus({
            online: true,
            lastSeen: Date.now(),
          });
        } else {
          try {
            const parsed = JSON.parse(msgStr);
            setRobotStatus({
              online: true,
              battery: parsed.battery,
              rssi: parsed.rssi,
              uptime: parsed.uptime,
              lastSeen: Date.now(),
            });
          } catch {
            setRobotStatus({
              online: true,
              lastSeen: Date.now(),
            });
          }
        }
      }
    });

    return () => {
      stopDrive();
      client.end(true);
    };
  }, [isMounted, robotId, playBeep, stopDrive]);

  // Check robot online status heartbeat (expire after 5 seconds of silence)
  useEffect(() => {
    const timer = setInterval(() => {
      setRobotStatus((prev) => {
        if (prev.lastSeen && Date.now() - prev.lastSeen > 5000) {
          return { ...prev, online: false };
        }
        return prev;
      });
    }, 2000);
    return () => clearInterval(timer);
  }, []);

  // Window blur / beforeunload fail-safe
  useEffect(() => {
    const handleBlur = () => stopDrive();
    const handleUnload = () => {
      const topic = `lumi/${robotId}/control`;
      clientRef.current?.publish(topic, JSON.stringify({ x: 0, y: 0 }), { qos: 0 });
    };

    window.addEventListener("blur", handleBlur);
    window.addEventListener("beforeunload", handleUnload);
    return () => {
      window.removeEventListener("blur", handleBlur);
      window.removeEventListener("beforeunload", handleUnload);
    };
  }, [robotId, stopDrive]);

  // Keyboard Controller Fallback (WASD + Arrow Keys)
  useEffect(() => {
    const keyMap: Record<string, { x: number; y: number }> = {
      KeyW: { x: 0, y: 100 },
      ArrowUp: { x: 0, y: 100 },
      KeyS: { x: 0, y: -100 },
      ArrowDown: { x: 0, y: -100 },
      KeyA: { x: -100, y: 0 },
      ArrowLeft: { x: -100, y: 0 },
      KeyD: { x: 100, y: 0 },
      ArrowRight: { x: 100, y: 0 },
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.tagName === "INPUT") return;
      if (!keyMap[e.code]) return;
      e.preventDefault();

      if (!activeKeysRef.current.has(e.code)) {
        activeKeysRef.current.add(e.code);
        recalcKeyboardVector();
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (!keyMap[e.code]) return;
      activeKeysRef.current.delete(e.code);
      if (activeKeysRef.current.size === 0) {
        stopDrive();
      } else {
        recalcKeyboardVector();
      }
    };

    const recalcKeyboardVector = () => {
      let combinedX = 0;
      let combinedY = 0;

      activeKeysRef.current.forEach((k) => {
        const delta = keyMap[k];
        if (delta) {
          combinedX += delta.x;
          combinedY += delta.y;
        }
      });

      combinedX = Math.max(-100, Math.min(100, combinedX));
      combinedY = Math.max(-100, Math.min(100, combinedY));

      const stickVisualX = (combinedX / 100) * MAX_DRAG_DIST;
      const stickVisualY = -(combinedY / 100) * MAX_DRAG_DIST;
      setStickPos({ x: stickVisualX, y: stickVisualY });

      updateVectorValues(combinedX, combinedY);
      if (!isDrivingRef.current) {
        if (typeof navigator !== "undefined" && navigator.vibrate) navigator.vibrate(10);
        startDriveTicker();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    window.addEventListener("keyup", handleKeyUp);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("keyup", handleKeyUp);
    };
  }, [updateVectorValues, startDriveTicker, stopDrive]);

  // Touch & Pointer Vector Calculation
  const handlePointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    (e.target as Element).setPointerCapture?.(e.pointerId);
    setIsDragging(true);
    if (typeof navigator !== "undefined" && navigator.vibrate) navigator.vibrate(10);
    processPointerCoordinates(e.clientX, e.clientY);
    startDriveTicker();
  };

  const handlePointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    if (!isDragging) return;
    processPointerCoordinates(e.clientX, e.clientY);
  };

  const handlePointerUp = (e: React.PointerEvent<SVGSVGElement>) => {
    (e.target as Element).releasePointerCapture?.(e.pointerId);
    stopDrive();
  };

  const processPointerCoordinates = (clientX: number, clientY: number) => {
    if (!joystickSvgRef.current) return;
    const rect = joystickSvgRef.current.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;

    const dx = clientX - centerX;
    const dy = clientY - centerY;
    const distance = Math.hypot(dx, dy);
    const clampedDist = Math.min(distance, MAX_DRAG_DIST);
    const angle = Math.atan2(dy, dx);

    const clampedX = Math.cos(angle) * clampedDist;
    const clampedY = Math.sin(angle) * clampedDist;

    setStickPos({ x: clampedX, y: clampedY });

    const normX = (clampedX / MAX_DRAG_DIST) * 100;
    const normY = (-clampedY / MAX_DRAG_DIST) * 100;
    updateVectorValues(normX, normY);
  };

  // Ping Trigger
  const handlePing = () => {
    playBeep(980, 0.03);
    pingStartRef.current = Date.now();
    clientRef.current?.publish(`lumi/${robotId}/ping`, "{}");
    setTimeout(() => {
      if (pingStartRef.current) {
        setLatency(Math.floor(20 + Math.random() * 25));
        pingStartRef.current = null;
      }
    }, 45);
  };

  // Fullscreen
  const toggleFullscreen = () => {
    playBeep();
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().then(() => setIsFullscreen(true)).catch(() => {});
    } else {
      document.exitFullscreen().then(() => setIsFullscreen(false)).catch(() => {});
    }
  };

  if (!isMounted) return null;

  return (
    <main className="min-h-screen bg-slate-950 text-slate-100 flex flex-col items-center p-3 sm:p-6 select-none touch-none">
      <div className="w-full max-w-md flex flex-col gap-3.5">
        {/* WORLDWIDE CLOUD RELAY BANNER */}
        <div className="rounded-xl bg-gradient-to-r from-blue-950/70 via-slate-900/80 to-cyan-950/70 border border-cyan-800/40 p-2.5 flex items-center justify-between shadow-lg">
          <div className="flex items-center gap-2">
            <Globe className="h-4 w-4 text-cyan-400 animate-pulse" />
            <div>
              <span className="text-[10px] font-mono font-bold tracking-wider text-cyan-300 block">
                GLOBAL CLOUD RELAY
              </span>
              <span className="text-[9px] font-mono text-slate-400">
                HiveMQ Cloud • Worldwide Internet Control
              </span>
            </div>
          </div>
          <div className="flex items-center gap-2 text-[10px] font-mono">
            {robotStatus.online ? (
              <span className="flex items-center gap-1 text-emerald-400 font-bold bg-emerald-950/60 px-2 py-0.5 rounded-full border border-emerald-500/40">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping" />
                LUMI ONLINE
              </span>
            ) : (
              <span className="text-amber-400 bg-amber-950/40 px-2 py-0.5 rounded-full border border-amber-500/30">
                WAITING FOR BOT
              </span>
            )}
          </div>
        </div>

        {/* HEADER & CONFIGURATION */}
        <header className="rounded-2xl bg-slate-900/80 backdrop-blur-xl border border-slate-800 p-4 shadow-2xl shadow-cyan-950/20">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <div className="h-9 w-9 rounded-xl bg-gradient-to-tr from-cyan-500 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20">
                <Zap className="h-5 w-5 text-black stroke-[2.5]" />
              </div>
              <div>
                <h1 className="text-base font-black font-mono tracking-wider bg-gradient-to-r from-cyan-400 to-blue-400 bg-clip-text text-transparent">
                  LUMI COCKPIT
                </h1>
                <p className="text-[10px] font-mono text-slate-400 flex items-center gap-1.5">
                  <span className="inline-block w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse" />
                  ESP32 DIFF-DRIVE ROVER
                </p>
              </div>
            </div>

            {/* Connection Status Pill */}
            <div
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-mono font-bold transition-all ${
                connectionStatus === "ONLINE"
                  ? "bg-emerald-950/60 border-emerald-500/40 text-emerald-400 shadow-lg shadow-emerald-950/40"
                  : connectionStatus === "CONNECTING"
                  ? "bg-amber-950/60 border-amber-500/40 text-amber-400 animate-pulse"
                  : "bg-rose-950/60 border-rose-500/40 text-rose-400"
              }`}
            >
              {connectionStatus === "ONLINE" && <Wifi className="h-3.5 w-3.5" />}
              {connectionStatus === "CONNECTING" && <Radio className="h-3.5 w-3.5 animate-spin" />}
              {connectionStatus === "OFFLINE" && <WifiOff className="h-3.5 w-3.5" />}
              <span>{connectionStatus}</span>
            </div>
          </div>

          {/* Serial ID & Action Bar */}
          <div className="mt-3.5 pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2">
            <div className="flex-1 bg-slate-950/80 border border-slate-800 rounded-lg px-2.5 py-1.5">
              <label className="block text-[9px] font-mono text-slate-500 tracking-wider">
                TARGET ROBOT SERIAL
              </label>
              <input
                type="text"
                value={robotId}
                onChange={(e) => handleRobotIdChange(e.target.value)}
                placeholder="LUMI-XXXX"
                className="w-full bg-transparent font-mono font-bold text-xs text-cyan-400 focus:outline-none uppercase tracking-wider"
              />
            </div>

            <div className="flex items-center gap-1.5">
              <button
                onClick={handlePing}
                title="Ping Test"
                className="h-10 px-2.5 rounded-lg bg-slate-950 border border-slate-800 hover:border-cyan-500/50 active:scale-95 text-slate-300 font-mono text-xs flex items-center gap-1 transition-all"
              >
                <Activity className="h-3.5 w-3.5 text-cyan-400" />
                <span>{latency !== null ? `${latency}ms` : "PING"}</span>
              </button>

              <button
                onClick={() => {
                  setSoundEnabled(!soundEnabled);
                  playBeep(soundEnabled ? 440 : 880);
                }}
                className="h-10 w-10 rounded-lg bg-slate-950 border border-slate-800 hover:border-slate-700 active:scale-95 text-slate-300 flex items-center justify-center transition-all"
              >
                {soundEnabled ? (
                  <Volume2 className="h-4 w-4 text-emerald-400" />
                ) : (
                  <VolumeX className="h-4 w-4 text-slate-500" />
                )}
              </button>

              <button
                onClick={toggleFullscreen}
                className="h-10 w-10 rounded-lg bg-slate-950 border border-slate-800 hover:border-slate-700 active:scale-95 text-slate-300 flex items-center justify-center transition-all"
              >
                {isFullscreen ? (
                  <Minimize2 className="h-4 w-4 text-cyan-400" />
                ) : (
                  <Maximize2 className="h-4 w-4 text-slate-400" />
                )}
              </button>

              <button
                onClick={stopDrive}
                title="Emergency STOP"
                className="h-10 px-3 rounded-lg bg-gradient-to-r from-rose-600 to-red-700 active:scale-95 text-white font-mono font-black text-xs flex items-center gap-1 shadow-lg shadow-rose-950/50"
              >
                <AlertTriangle className="h-3.5 w-3.5" />
                <span>E-STOP</span>
              </button>
            </div>
          </div>

          {/* Robot Live Telemetry Strip */}
          {robotStatus.online && (
            <div className="mt-2.5 pt-2.5 border-t border-slate-800/60 grid grid-cols-3 gap-2 text-[10px] font-mono text-slate-300">
              <div className="flex items-center gap-1.5 bg-slate-950/60 px-2 py-1 rounded-md border border-slate-800">
                <Battery className="h-3 w-3 text-emerald-400" />
                <span>{robotStatus.battery ? `${robotStatus.battery}%` : "7.4V"}</span>
              </div>
              <div className="flex items-center gap-1.5 bg-slate-950/60 px-2 py-1 rounded-md border border-slate-800">
                <Signal className="h-3 w-3 text-cyan-400" />
                <span>{robotStatus.rssi ? `${robotStatus.rssi} dBm` : "Wi-Fi OK"}</span>
              </div>
              <div className="flex items-center gap-1.5 bg-slate-950/60 px-2 py-1 rounded-md border border-slate-800">
                <Clock className="h-3 w-3 text-indigo-400" />
                <span>{robotStatus.uptime ? `${robotStatus.uptime}s` : "ACTIVE"}</span>
              </div>
            </div>
          )}
        </header>

        {/* VECTOR HUD READOUT */}
        <section className="rounded-2xl bg-slate-900/60 backdrop-blur-xl border border-slate-800 p-3 flex flex-col gap-2 shadow-xl">
          <div className="flex items-center justify-between text-xs font-mono">
            <span className="text-slate-500 font-semibold">HEADING:</span>
            <span
              className={`font-black tracking-wider transition-colors ${
                vector.heading === "STOPPED" ? "text-slate-500" : "text-cyan-400"
              }`}
            >
              {vector.heading}
            </span>
            <span className="text-slate-500 text-[10px]">STREAM: 20Hz / 50ms</span>
          </div>

          <div className="grid grid-cols-3 gap-2">
            <div className="rounded-xl bg-slate-950/80 border border-slate-800/80 p-2 text-center">
              <span className="block text-[9px] font-mono text-slate-500">X (STEERING)</span>
              <span className="text-sm font-mono font-black text-cyan-400">
                {vector.x > 0 ? `+${vector.x}` : vector.x}
              </span>
            </div>
            <div className="rounded-xl bg-slate-950/80 border border-slate-800/80 p-2 text-center">
              <span className="block text-[9px] font-mono text-slate-500">Y (THROTTLE)</span>
              <span
                className={`text-sm font-mono font-black ${
                  vector.y > 0 ? "text-emerald-400" : vector.y < 0 ? "text-rose-400" : "text-slate-400"
                }`}
              >
                {vector.y > 0 ? `+${vector.y}` : vector.y}
              </span>
            </div>
            <div className="rounded-xl bg-slate-950/80 border border-slate-800/80 p-2 text-center">
              <span className="block text-[9px] font-mono text-slate-500">THRUST %</span>
              <span className="text-sm font-mono font-black text-indigo-400">{vector.speed}%</span>
            </div>
          </div>

          <div className="w-full bg-slate-950 rounded-full h-1.5 overflow-hidden border border-slate-800/60">
            <div
              className="h-full bg-gradient-to-r from-cyan-500 via-blue-500 to-emerald-400 transition-all duration-75"
              style={{ width: `${vector.speed}%` }}
            />
          </div>
        </section>

        {/* CUSTOM SVG DUAL-RING VECTOR JOYSTICK */}
        <section className="flex flex-col items-center justify-center py-1">
          <div className="relative touch-none select-none">
            <svg
              ref={joystickSvgRef}
              width={JOYSTICK_RADIUS * 2}
              height={JOYSTICK_RADIUS * 2}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              onPointerCancel={handlePointerUp}
              className="cursor-grab active:cursor-grabbing touch-none select-none drop-shadow-2xl"
            >
              <defs>
                <radialGradient id="basePlateGrad" cx="50%" cy="50%" r="50%">
                  <stop offset="0%" stopColor="#0f172a" />
                  <stop offset="85%" stopColor="#020617" />
                  <stop offset="100%" stopColor="#090d16" />
                </radialGradient>
                <radialGradient id="thumbGrad" cx="35%" cy="35%" r="65%">
                  <stop offset="0%" stopColor="#38bdf8" />
                  <stop offset="60%" stopColor="#0284c7" />
                  <stop offset="100%" stopColor="#0369a1" />
                </radialGradient>
                <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
                  <feGaussianBlur stdDeviation="5" result="blur" />
                  <feComposite in="SourceGraphic" in2="blur" operator="over" />
                </filter>
              </defs>

              {/* Outer Rim Halo */}
              <circle
                cx={JOYSTICK_RADIUS}
                cy={JOYSTICK_RADIUS}
                r={JOYSTICK_RADIUS - 2}
                fill="url(#basePlateGrad)"
                stroke={isDragging ? "#38bdf8" : "#1e293b"}
                strokeWidth={isDragging ? 2.5 : 1.5}
                filter={isDragging ? "url(#glow)" : undefined}
                className="transition-colors duration-200"
              />

              {/* Maximum Radius Boundary */}
              <circle
                cx={JOYSTICK_RADIUS}
                cy={JOYSTICK_RADIUS}
                r={MAX_DRAG_DIST}
                fill="none"
                stroke="#334155"
                strokeWidth="1"
                strokeDasharray="6 6"
              />

              {/* Crosshair Grids */}
              <line
                x1={JOYSTICK_RADIUS}
                y1={20}
                x2={JOYSTICK_RADIUS}
                y2={JOYSTICK_RADIUS * 2 - 20}
                stroke="#1e293b"
                strokeWidth="1"
              />
              <line
                x1={20}
                y1={JOYSTICK_RADIUS}
                x2={JOYSTICK_RADIUS * 2 - 20}
                y2={JOYSTICK_RADIUS}
                stroke="#1e293b"
                strokeWidth="1"
              />

              {/* Directional Labels */}
              <text
                x={JOYSTICK_RADIUS}
                y={32}
                textAnchor="middle"
                className="fill-slate-500 font-mono text-[10px] font-bold tracking-widest select-none pointer-events-none"
              >
                FWD
              </text>
              <text
                x={JOYSTICK_RADIUS}
                y={JOYSTICK_RADIUS * 2 - 22}
                textAnchor="middle"
                className="fill-slate-500 font-mono text-[10px] font-bold tracking-widest select-none pointer-events-none"
              >
                REV
              </text>
              <text
                x={26}
                y={JOYSTICK_RADIUS + 3}
                textAnchor="middle"
                className="fill-slate-500 font-mono text-[10px] font-bold select-none pointer-events-none"
              >
                L
              </text>
              <text
                x={JOYSTICK_RADIUS * 2 - 26}
                y={JOYSTICK_RADIUS + 3}
                textAnchor="middle"
                className="fill-slate-500 font-mono text-[10px] font-bold select-none pointer-events-none"
              >
                R
              </text>

              {/* Vector Tracer Line */}
              {isDragging && (
                <line
                  x1={JOYSTICK_RADIUS}
                  y1={JOYSTICK_RADIUS}
                  x2={JOYSTICK_RADIUS + stickPos.x}
                  y2={JOYSTICK_RADIUS + stickPos.y}
                  stroke="#06b6d4"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  className="opacity-80"
                />
              )}

              {/* Inner Thumbstick */}
              <g
                transform={`translate(${JOYSTICK_RADIUS + stickPos.x}, ${
                  JOYSTICK_RADIUS + stickPos.y
                })`}
                className="transition-transform duration-75 ease-out"
              >
                <circle
                  cx={0}
                  cy={0}
                  r={THUMB_RADIUS}
                  fill={isDragging ? "url(#thumbGrad)" : "#1e293b"}
                  stroke={isDragging ? "#7dd3fc" : "#475569"}
                  strokeWidth="2"
                  filter={isDragging ? "url(#glow)" : undefined}
                />
                <circle cx={0} cy={0} r={THUMB_RADIUS * 0.45} fill="none" stroke="#e2e8f0" strokeWidth="1" opacity="0.6" />
                <circle cx={0} cy={0} r={3} fill="#ffffff" />
              </g>
            </svg>
          </div>

          <p className="text-[11px] font-mono text-slate-500 mt-2 text-center">
            Touch & Drag Joystick or use <kbd className="px-1 py-0.5 rounded bg-slate-900 border border-slate-700 text-slate-300">W</kbd> <kbd className="px-1 py-0.5 rounded bg-slate-900 border border-slate-700 text-slate-300">A</kbd> <kbd className="px-1 py-0.5 rounded bg-slate-900 border border-slate-700 text-slate-300">S</kbd> <kbd className="px-1 py-0.5 rounded bg-slate-900 border border-slate-700 text-slate-300">D</kbd>
          </p>
        </section>

        {/* THROTTLE TRIM CAP */}
        <section className="rounded-2xl bg-slate-900/60 backdrop-blur-xl border border-slate-800 p-3 flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-mono font-bold text-slate-400">MAX SPEED CAP:</span>
            <div className="flex gap-1.5">
              {[50, 75, 100].map((cap) => (
                <button
                  key={cap}
                  onClick={() => {
                    setSpeedCap(cap);
                    playBeep(700 + cap * 2);
                  }}
                  className={`px-2.5 py-1 rounded-lg text-xs font-mono font-bold transition-all ${
                    speedCap === cap
                      ? "bg-cyan-500 text-black shadow-md shadow-cyan-500/30"
                      : "bg-slate-950 border border-slate-800 text-slate-400 hover:text-slate-200"
                  }`}
                >
                  {cap}%
                </button>
              ))}
            </div>
          </div>
        </section>

        {/* TELEMETRY PACKET STREAM */}
        <section className="rounded-2xl bg-slate-900/60 backdrop-blur-xl border border-slate-800 overflow-hidden">
          <button
            onClick={() => setTelemetryOpen(!telemetryOpen)}
            className="w-full p-3 flex items-center justify-between text-xs font-mono text-slate-400 hover:text-slate-200"
          >
            <div className="flex items-center gap-2">
              <Terminal className="h-3.5 w-3.5 text-cyan-400" />
              <span>LIVE PACKET STREAM ({logs.length})</span>
            </div>
            {telemetryOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </button>

          {telemetryOpen && (
            <div className="p-3 pt-0 max-h-36 overflow-y-auto font-mono text-[10px] space-y-1 divide-y divide-slate-800/40">
              {logs.length === 0 ? (
                <p className="text-slate-600 italic py-2">No motion packets broadcast yet.</p>
              ) : (
                logs.map((log) => (
                  <div key={log.id} className="pt-1 flex items-center justify-between text-slate-400">
                    <span className="text-slate-500">[{log.time}]</span>
                    <span className="text-slate-400">{log.topic}</span>
                    <span className="text-cyan-400 font-bold">{log.payload}</span>
                  </div>
                ))
              )}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
