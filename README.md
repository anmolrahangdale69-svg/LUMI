# Lumi ESP32 Differential Drive Robot Controller

Worldwide cloud controller system for Lumi (ESP32 differential drive robot), supporting both **Web Browser Cockpit (Next.js)** and **Native Android App (Jetpack Compose)**.

---

## Deploying to Vercel (Fixing 404 Error)

This project is pre-configured with `vercel.json` and a dedicated `web/` application directory.

### Method 1: Automatic Zero-Config Deployment
1. Connect this GitHub repository to [Vercel](https://vercel.com).
2. The included `vercel.json` automatically runs the build command:
   ```json
   {
     "buildCommand": "cd web && npm install && npm run build",
     "outputDirectory": "web/.next"
   }
   ```
3. Click **Deploy**. Vercel will build and launch the site at your `.vercel.app` URL with zero 404 errors.

### Method 2: Set Root Directory in Vercel Settings
If you prefer standard Next.js native builds in Vercel:
1. In Vercel, go to your Project **Settings** ➔ **General**.
2. Under **Root Directory**, click **Edit** and set it to: `web`.
3. Set **Framework Preset** to: `Next.js`.
4. Click **Save** and trigger a **Redeploy**.

---

## System Architecture

- **Web Cockpit**: `web/app/page.tsx` — Next.js 14 App Router, Tailwind CSS, MQTT WebSocket client.
- **Android App**: `app/` — Kotlin + Jetpack Compose with dual-ring touch joystick and haptics.
- **Cloud MQTT Broker**: `broker.hivemq.com` (WebSockets on port 8884, ESP32 on port 1883).
- **Topic Streams**:
  - `lumi/<ROBOT_ID>/control`: 20Hz vector inputs `{"x": integer, "y": integer}`.
  - `lumi/<ROBOT_ID>/status`: 2-way robot heartbeat (battery %, Wi-Fi RSSI, uptime).
