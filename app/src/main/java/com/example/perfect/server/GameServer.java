package com.example.perfect.server;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的 HTTP + WebSocket 服务器。
 * 监听同一端口，同时处理 HTTP GET（返回 index.html）和 WebSocket（游戏逻辑）。
 * 绑定 0.0.0.0，支持局域网访问。
 * 游戏逻辑完全参照 Node.js server.js 1:1 复刻。
 */
public class GameServer {

    private static final String TAG = "GameServer";

    private final int port;
    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final Map<String, GameSession> games = new ConcurrentHashMap<>();
    private final Map<String, String> playerToGame = new ConcurrentHashMap<>();
    private final Map<String, WsConn> waitingPlayers = new ConcurrentHashMap<>();
    private final Map<String, WsConn> playerConnections = new ConcurrentHashMap<>();
    private final Map<Thread, WsConn> threadToConn = new ConcurrentHashMap<>();

    private Thread serverThread;

    public GameServer(int port, Context context) {
        this.port = port;
        this.context = context;
    }

    public void start() {
        running = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "Server listening on 0.0.0.0:" + port);
                while (running) {
                    try {
                        Socket s = serverSocket.accept();
                        new Thread(() -> handleConn(s), "ConnHandler").start();
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "accept error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "server socket error", e);
            }
        }, "GS-Main");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
        for (WsConn c : playerConnections.values()) c.close();
        for (WsConn c : waitingPlayers.values()) c.close();
    }

    // ==================== 通信工具 ====================

    private void sendRaw(String pid, String json) {
        WsConn c = playerConnections.get(pid);
        if (c != null && c.isConnAlive()) c.sendText(json);
    }

    private void safeSendJson(String pid, JSONObject m) {
        try { sendRaw(pid, m.toString()); } catch (Exception e) { /* ignore */ }
    }

    private void safeBroadcastJson(GameSession g, JSONObject m) {
        try { for (String p : g.players) sendRaw(p, m.toString()); } catch (Exception e) { /* ignore */ }
    }

    // ==================== 连接分发 ====================

    private void handleConn(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();

            byte[] buf = new byte[8192];
            int n = 0;
            while (n < buf.length) {
                int b = in.read();
                if (b == -1) { socket.close(); return; }
                buf[n++] = (byte) b;
                if (n >= 4 && buf[n-4]=='\r' && buf[n-3]=='\n' && buf[n-2]=='\r' && buf[n-1]=='\n')
                    break;
            }

            String hdr = new String(buf, 0, n, StandardCharsets.UTF_8);
            String[] lines = hdr.split("\r\n");
            if (lines.length == 0) { socket.close(); return; }

            String[] rp = lines[0].split(" ");
            if (rp.length < 2) { socket.close(); return; }
            String method = rp[0], path = rp[1];

            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int ci = lines[i].indexOf(':');
                if (ci > 0) {
                    h.put(lines[i].substring(0, ci).trim().toLowerCase(),
                          lines[i].substring(ci + 1).trim());
                }
            }

            String upgrade = h.get("upgrade");
            if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                wsHandshake(socket, h);
            } else {
                httpReply(socket, method, path);
            }
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ex) {}
        }
    }

    // ==================== HTTP ====================

    private void httpReply(Socket socket, String method, String path) throws IOException {
        OutputStream out = socket.getOutputStream();
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            String html;
            try (InputStream is = context.getAssets().open("index.html")) {
                byte[] b = new byte[is.available()]; is.read(b);
                html = new String(b, StandardCharsets.UTF_8);
            } catch (IOException e) {
                html = "<html><body><h1>Server Running</h1></body></html>";
            }
            writeResp(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } else {
            writeResp(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
        }
        socket.close();
    }

    private void writeResp(OutputStream out, int code, String ct, byte[] body) throws IOException {
        String st = (code == 200) ? "OK" : "Not Found";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(st).append("\r\n");
        sb.append("Content-Type: ").append(ct).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // ==================== WebSocket 握手 ====================

    private void wsHandshake(Socket socket, Map<String, String> h) throws IOException, NoSuchAlgorithmException {
        String key = h.get("sec-websocket-key");
        if (key == null) { socket.close(); return; }

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] ab = md.digest((key + "258EAFA5-E914-47DA-95CA-5AB9DC11B85A").getBytes(StandardCharsets.UTF_8));
        String ak = Base64.encodeToString(ab, Base64.NO_WRAP);

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Accept: ").append(ak).append("\r\n\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        socket.setSoTimeout(0);
        WsConn conn = new WsConn(socket);
        conn.start();
    }

    // ==================== WebSocket 连接 ====================

    private class WsConn extends Thread {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean alive = true;
        private String myPid = null;

        WsConn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            setDaemon(true);
        }

        @Override
        public void run() {
            threadToConn.put(Thread.currentThread(), this);
            try {
                while (alive && running) {
                    int fb = in.read();
                    if (fb == -1) break;
                    int op = fb & 0x0F;

                    if (op == 0x8) break;
                    if (op == 0x9) { skipFrame(); sendPong(); continue; }
                    if (op == 0xA) { skipFrame(); continue; }

                    int sb = in.read();
                    if (sb == -1) break;
                    boolean masked = (sb & 0x80) != 0;
                    long plen = sb & 0x7F;
                    if (plen == 126) {
                        int a = in.read(), b2 = in.read();
                        if (a == -1 || b2 == -1) break;
                        plen = ((a << 8) | b2) & 0xFFFF;
                    } else if (plen == 127) {
                        long v = 0;
                        for (int i = 0; i < 8; i++) {
                            int a = in.read();
                            if (a == -1) throw new IOException();
                            v = (v << 8) | a;
                        }
                        plen = v;
                    }

                    byte[] mask = null;
                    if (masked) { mask = new byte[4]; readFully(mask); }

                    byte[] payload = new byte[(int) plen];
                    if (plen > 0) {
                        readFully(payload);
                        if (mask != null)
                            for (int i = 0; i < payload.length; i++)
                                payload[i] ^= mask[i & 3];
                    }

                    if (op == 0x1) {
                        onWsMessage(new String(payload, StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                // closed
            } finally {
                threadToConn.remove(Thread.currentThread());
                doDisconnect();
                close();
            }
        }

        private void skipFrame() throws IOException {
            int sb = in.read();
            if (sb == -1) throw new IOException();
            boolean m = (sb & 0x80) != 0;
            long pl = sb & 0x7F;
            if (pl == 126) { in.read(); in.read(); }
            else if (pl == 127) { for (int i = 0; i < 8; i++) in.read(); }
            if (m) readFully(new byte[4]);
            if (pl > 0) readFully(new byte[(int) pl]);
        }

        private void readFully(byte[] b) throws IOException {
            int o = 0;
            while (o < b.length) {
                int r = in.read(b, o, b.length - o);
                if (r == -1) throw new IOException();
                o += r;
            }
        }

        private void sendPong() throws IOException {
            synchronized (out) { out.write(new byte[]{(byte) 0x8A, 0}); out.flush(); }
        }

        void sendText(String msg) {
            if (!alive) return;
            try {
                byte[] d = msg.getBytes(StandardCharsets.UTF_8);
                synchronized (out) {
                    if (d.length <= 125) {
                        out.write(new byte[]{(byte) 0x81, (byte) d.length});
                    } else if (d.length <= 65535) {
                        out.write(new byte[]{(byte) 0x81, 126});
                        out.write(new byte[]{(byte) (d.length >> 8), (byte) (d.length & 0xFF)});
                    } else {
                        out.write(new byte[]{(byte) 0x81, 127});
                        for (int i = 7; i >= 0; i--) out.write((byte) (d.length >> (8 * i)));
                    }
                    out.write(d);
                    out.flush();
                }
            } catch (IOException e) { /* ignore */ }
        }

        void close() {
            alive = false;
            try { socket.close(); } catch (IOException e) {}
        }

        boolean isConnAlive() { return alive; }
        String getMyPid() { return myPid; }
        void setMyPid(String pid) { this.myPid = pid; }
    }

    // ==================== 消息处理 ====================

    private void onWsMessage(String raw) {
        try {
            JSONObject m = new JSONObject(raw);
            String pid = m.getString("id");
            String type = m.getString("type");
            switch (type) {
                case "join":           doJoin(pid); break;
                case "deal_request":   doDealRequest(pid); break;
                case "play":           doPlay(pid, m.getJSONArray("cards")); break;
                case "pass":           doPass(pid); break;
                case "recall_request": doRecall(pid); break;
                case "game_over":      doGameOver(pid, m.getString("winner")); break;
                case "continue":       doContinue(pid); break;
                case "req_restart":    doReqRestart(pid); break;
                case "resp_restart":   doRespRestart(pid, m.getBoolean("agree")); break;
                case "ping":           sendRaw(pid, "{\"type\":\"pong\"}"); break;
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void doDisconnect() {
        WsConn me = threadToConn.get(Thread.currentThread());
        if (me == null) return;
        String pid = me.getMyPid();
        if (pid == null) return;

        waitingPlayers.remove(pid);
        playerConnections.remove(pid);

        GameSession g = getGame(pid);
        if (g == null) return;

        g.online.put(pid, false);
        String opp = g.opponent(pid);
        if (opp != null) {
            sendRaw(opp, "{\"type\":\"leave\",\"id\":\"" + pid + "\"}");
        }
        sendOnlineStatus(g);

        if (g.bothOffline()) removeGame(g);
    }

    // ==================== JSON 构建辅助 ====================

    private JSONObject json(String k1, Object v1) {
        JSONObject m = new JSONObject();
        try { m.put(k1, v1); } catch (JSONException e) {}
        return m;
    }

    private JSONObject json(String k1, Object v1, String k2, Object v2) {
        JSONObject m = new JSONObject();
        try { m.put(k1, v1); m.put(k2, v2); } catch (JSONException e) {}
        return m;
    }

    private JSONObject json(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        JSONObject m = new JSONObject();
        try { m.put(k1, v1); m.put(k2, v2); m.put(k3, v3); } catch (JSONException e) {}
        return m;
    }

    // ==================== 更多通信工具 ====================

    private void sendOnlineStatus(GameSession g) {
        List<String> ids = new ArrayList<>();
        for (String p : g.players) if (g.online.get(p)) ids.add(p);
        try {
            JSONObject m = new JSONObject();
            m.put("type", "online_count");
            m.put("count", ids.size());
            m.put("players", new JSONArray(ids));
            for (String p : g.players) sendRaw(p, m.toString());
        } catch (JSONException e) { /* ignore */ }
    }

    // ==================== 游戏管理 ====================

    private GameSession getGame(String pid) {
        String gid = playerToGame.get(pid);
        return gid != null ? games.get(gid) : null;
    }

    private GameSession createGame(String a, String b) {
        GameSession g = new GameSession(a, b);
        games.put(g.id, g);
        playerToGame.put(a, g.id);
        playerToGame.put(b, g.id);
        return g;
    }

    private void removeGame(GameSession g) {
        for (String p : g.players) playerToGame.remove(p);
        games.remove(g.id);
    }

    private void startDeal(GameSession g) throws JSONException {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = DeckUtil.dealCards(g.players);
        @SuppressWarnings("unchecked")
        Map<String, List<Card>> hands = (Map<String, List<Card>>) r.get("hands");
        String ft = (String) r.get("firstTurn");

        g.hands = hands;
        g.currentTurn = ft;
        g.lastPlay = null;
        g.lastPlayPlayer = null;
        g.winner = null;
        g.phase = "PLAYING";
        g.dealRequests.clear();
        g.continueRequests.clear();

        for (String pid : g.players) {
            JSONObject m = new JSONObject();
            m.put("type", "deal_result");
            JSONObject hj = new JSONObject();
            JSONArray ha = new JSONArray();
            for (Card c : hands.get(pid)) ha.put(c.toJson());
            hj.put(pid, ha);
            m.put("hands", hj);
            m.put("firstTurn", ft);
            sendRaw(pid, m.toString());
        }
    }

    // ==================== 玩家连接 / 断开 ====================

    private void doJoin(String pid) {
        WsConn me = threadToConn.get(Thread.currentThread());

        WsConn old = playerConnections.get(pid);
        if (old != null && old != me) old.close();

        playerConnections.put(pid, me);
        if (me != null) me.setMyPid(pid);

        GameSession g = getGame(pid);
        if (g != null) { restorePlayer(pid, g); return; }

        Iterator<Map.Entry<String, WsConn>> it = waitingPlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WsConn> e = it.next();
            String wid = e.getKey();
            WsConn wh = e.getValue();
            if (!wid.equals(pid) && wh.isConnAlive()) {
                it.remove();
                GameSession ng = createGame(pid, wid);
                ng.online.put(pid, true);
                ng.online.put(wid, true);
                JSONObject pm = json("type", "paired");
                sendRaw(pid, pm.toString());
                sendRaw(wid, pm.toString());
                sendOnlineStatus(ng);
                return;
            }
        }

        if (me != null) waitingPlayers.put(pid, me);
        JSONObject wm = json("type", "waiting");
        sendRaw(pid, wm.toString());
    }

    private void restorePlayer(String pid, GameSession g) {
        g.online.put(pid, true);
        String opp = g.opponent(pid);

        if (opp != null && g.online.get(opp)) {
            sendRaw(opp, json("type", "opponent_reconnect", "id", pid).toString());
        }
        sendOnlineStatus(g);

        if ("PLAYING".equals(g.phase) && g.hands.containsKey(pid)) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "deal_result");
                JSONObject hj = new JSONObject();
                JSONArray ha = new JSONArray();
                for (Card c : g.hands.get(pid)) ha.put(c.toJson());
                hj.put(pid, ha);
                m.put("hands", hj);
                m.put("firstTurn", g.currentTurn);
                m.put("isRestore", true);
                m.put("opponentCount", g.hands.containsKey(opp) ? g.hands.get(opp).size() : 0);
                sendRaw(pid, m.toString());
            } catch (JSONException e) { /* ignore */ }

            if (g.lastPlay != null && !g.lastPlay.isEmpty()) {
                try {
                    JSONObject rm = new JSONObject();
                    rm.put("type", "restore_last_play");
                    JSONArray ca = new JSONArray();
                    for (Card c : g.lastPlay) ca.put(c.toJson());
                    rm.put("cards", ca);
                    sendRaw(pid, rm.toString());
                } catch (JSONException e) { /* ignore */ }
            }
        } else if ("GAME_OVER".equals(g.phase)) {
            sendRaw(pid, json("type", "game_over", "winner", g.winner).toString());
            if (opp != null && g.continueRequests.contains(opp)) {
                sendRaw(pid, json("type", "continue", "id", opp).toString());
            }
        } else {
            sendRaw(pid, json("type", "paired").toString());
        }
    }

    // ==================== 消息处理 ====================

    private void doDealRequest(String pid) {
        GameSession g = getGame(pid);
        if (g == null) return;
        g.dealRequests.add(pid);
        String opp = g.opponent(pid);
        if (opp != null) {
            safeSendJson(opp, json("type", "deal_request", "id", pid));
        }
        if (g.dealRequests.size() >= 2 && g.bothOnline()) {
            try { startDeal(g); } catch (JSONException e) { /* ignore */ }
        }
    }

    private void doPlay(String pid, JSONArray cardsJson) {
        GameSession g = getGame(pid);
        if (g == null) return;
        List<Card> cards = new ArrayList<>();
        try {
            for (int i = 0; i < cardsJson.length(); i++) {
                JSONObject o = cardsJson.getJSONObject(i);
                cards.add(new Card(o.getInt("id"), o.getString("val"),
                        o.getString("suit"), o.getString("color")));
            }
        } catch (JSONException e) { return; }

        g.lastPlay = cards;
        g.lastPlayPlayer = pid;
        g.currentTurn = g.opponent(pid);

        if (g.hands.containsKey(pid)) {
            Set<Integer> ids = ConcurrentHashMap.newKeySet();
            for (Card c : cards) ids.add(c.id);
            g.hands.get(pid).removeIf(c -> ids.contains(c.id));
        }

        String opp = g.opponent(pid);
        if (opp != null) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "play");
                m.put("id", pid);
                JSONArray ca = new JSONArray();
                for (Card c : cards) ca.put(c.toJson());
                m.put("cards", ca);
                sendRaw(opp, m.toString());
            } catch (JSONException e) { /* ignore */ }
        }
    }

    private void doPass(String pid) {
        GameSession g = getGame(pid);
        if (g == null) return;
        g.currentTurn = g.opponent(pid);
        String opp = g.opponent(pid);
        if (opp != null) {
            safeSendJson(opp, json("type", "pass", "id", pid));
        }
    }

    private void doRecall(String pid) {
        GameSession g = getGame(pid);
        if (g == null) return;
        if (g.lastPlay != null && g.lastPlayPlayer != null) {
            String target = g.lastPlayPlayer;
            List<Card> cards = g.lastPlay;
            if (!g.hands.containsKey(target)) g.hands.put(target, new ArrayList<>());
            g.hands.get(target).addAll(cards);
            g.lastPlay = null;
            g.lastPlayPlayer = null;
            g.currentTurn = target;

            try {
                JSONObject m = new JSONObject();
                m.put("type", "recall_result");
                m.put("player", target);
                JSONArray ca = new JSONArray();
                for (Card c : cards) ca.put(c.toJson());
                m.put("cards", ca);
                m.put("nextTurn", target);
                for (String p : g.players) sendRaw(p, m.toString());
            } catch (JSONException e) { /* ignore */ }
        } else {
            safeSendJson(pid, json("type", "recall_failed", "message", "当前无牌可撤回"));
        }
    }

    private void doGameOver(String pid, String winner) {
        GameSession g = getGame(pid);
        if (g == null) return;
        g.phase = "GAME_OVER";
        g.winner = winner;
        safeBroadcastJson(g, json("type", "game_over", "winner", winner));
    }

    private void doContinue(String pid) {
        GameSession g = getGame(pid);
        if (g == null) return;
        g.continueRequests.add(pid);
        String opp = g.opponent(pid);
        if (opp != null) {
            safeSendJson(opp, json("type", "continue", "id", pid));
        }
        if (g.continueRequests.size() >= 2) {
            try { startDeal(g); } catch (JSONException e) { /* ignore */ }
        }
    }

    private void doReqRestart(String pid) {
        GameSession g = getGame(pid);
        if (g == null) return;
        String opp = g.opponent(pid);
        if (opp != null) {
            safeSendJson(opp, json("type", "req_restart", "id", pid));
        }
    }

    private void doRespRestart(String pid, boolean agree) {
        GameSession g = getGame(pid);
        if (g == null) return;
        String opp = g.opponent(pid);
        if (opp != null) {
            try {
                JSONObject m = new JSONObject();
                m.put("type", "resp_restart");
                m.put("agree", agree);
                m.put("id", pid);
                sendRaw(opp, m.toString());
            } catch (JSONException e) { /* ignore */ }
        }
        if (agree) {
            g.phase = "WAITING";
            g.dealRequests.clear();
            g.continueRequests.clear();
            g.hands.clear();
            g.lastPlay = null;
            g.lastPlayPlayer = null;
            g.winner = null;
        }
    }
}
