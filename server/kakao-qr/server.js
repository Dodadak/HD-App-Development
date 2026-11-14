// 카카오 QR 로그인 중계 서버 (Node.js 18+, 추가 패키지 없음)
//
// 차 화면에는 QR만 띄우고, 실제 카카오 로그인은 휴대폰에서 한다. 이 서버가 그 사이를 이어 준다.
//   차  → POST /qr-sessions              새 QR 세션 만들기 → { id, url }   (url 을 QR로 띄움)
//   폰  → GET  /qr/:id                   카카오 로그인 화면으로 보냄
//   카카오 → GET /oauth/kakao?code&state  토큰 받고 사용자 정보 읽어 세션에 넣음
//   차  → GET  /qr-sessions/:id          { status: pending | done | expired, user? }  (2초마다)
//
// 실행:  KAKAO_REST_API_KEY=... PUBLIC_URL=https://내서버주소 node server.js
//   KAKAO_CLIENT_SECRET : 카카오 콘솔에서 Client Secret 을 켰을 때만
//   PORT                : 기본 8787
const http = require("http");
const crypto = require("crypto");

const REST_KEY = process.env.KAKAO_REST_API_KEY;
const SECRET = process.env.KAKAO_CLIENT_SECRET || "";
const PUBLIC_URL = (process.env.PUBLIC_URL || "").replace(/\/$/, "");
const PORT = Number(process.env.PORT || 8787);
const LIFETIME_MS = 3 * 60 * 1000; // QR 유효 시간 (차 화면과 같게)

if (!REST_KEY || !PUBLIC_URL) {
  console.error("KAKAO_REST_API_KEY 와 PUBLIC_URL 을 넣어 주세요. 예) KAKAO_REST_API_KEY=abc PUBLIC_URL=https://example.com node server.js");
  process.exit(1);
}
const REDIRECT_URI = `${PUBLIC_URL}/oauth/kakao`;

/** id → { status, created, user } . 메모리에만 둔다 (사용자 정보는 차가 한 번 가져가면 지움) */
const sessions = new Map();

function json(res, code, body) {
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}
function page(res, code, title, body) {
  res.writeHead(code, { "Content-Type": "text/html; charset=utf-8" });
  res.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><body style="font-family:sans-serif;padding:32px;text-align:center"><h2>${title}</h2><p>${body}</p></body>`);
}
function alive(s) {
  return s && Date.now() - s.created < LIFETIME_MS;
}

/** 카카오 전화번호 "+82 10-1234-5678" → "01012345678" */
function localPhone(p) {
  if (!p) return "";
  const d = p.replace(/[^0-9+]/g, "");
  return d.startsWith("+82") ? "0" + d.slice(3) : d.replace(/\+/g, "");
}

async function kakaoUser(code) {
  const form = new URLSearchParams({ grant_type: "authorization_code", client_id: REST_KEY, redirect_uri: REDIRECT_URI, code });
  if (SECRET) form.set("client_secret", SECRET);
  const tokenRes = await fetch("https://kauth.kakao.com/oauth/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=utf-8" },
    body: form,
  });
  const token = await tokenRes.json();
  if (!token.access_token) throw new Error("토큰 발급 실패: " + JSON.stringify(token));
  const meRes = await fetch("https://kapi.kakao.com/v2/user/me", { headers: { Authorization: `Bearer ${token.access_token}` } });
  const me = await meRes.json();
  const account = me.kakao_account || {};
  return {
    id: String(me.id),
    // 이름·전화번호는 카카오 비즈 앱 + 동의항목 검수가 끝나야 온다. 그 전에는 닉네임만
    name: account.name || (account.profile && account.profile.nickname) || "카카오 사용자",
    phone: localPhone(account.phone_number),
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, PUBLIC_URL);
  const parts = url.pathname.split("/").filter(Boolean);
  try {
    if (req.method === "POST" && url.pathname === "/qr-sessions") {
      const id = crypto.randomBytes(16).toString("hex");
      sessions.set(id, { status: "pending", created: Date.now() });
      return json(res, 200, { id, url: `${PUBLIC_URL}/qr/${id}` });
    }
    if (req.method === "GET" && parts[0] === "qr-sessions" && parts[1]) {
      const s = sessions.get(parts[1]);
      if (!alive(s)) { sessions.delete(parts[1]); return json(res, 200, { status: "expired" }); }
      if (s.status === "done") { sessions.delete(parts[1]); return json(res, 200, { status: "done", user: s.user }); }
      return json(res, 200, { status: "pending" });
    }
    if (req.method === "GET" && parts[0] === "qr" && parts[1]) {
      if (!alive(sessions.get(parts[1]))) return page(res, 410, "QR이 만료됐어요", "차 화면에서 새 QR을 찍어 주세요.");
      const auth = new URL("https://kauth.kakao.com/oauth/authorize");
      auth.search = new URLSearchParams({ client_id: REST_KEY, redirect_uri: REDIRECT_URI, response_type: "code", state: parts[1] }).toString();
      res.writeHead(302, { Location: auth.toString() });
      return res.end();
    }
    if (req.method === "GET" && url.pathname === "/oauth/kakao") {
      const id = url.searchParams.get("state");
      const s = sessions.get(id);
      if (!alive(s)) return page(res, 410, "QR이 만료됐어요", "차 화면에서 새 QR을 찍어 주세요.");
      if (url.searchParams.get("error")) return page(res, 400, "로그인을 취소했어요", "다시 하려면 QR을 다시 찍어 주세요.");
      s.user = await kakaoUser(url.searchParams.get("code"));
      s.status = "done";
      return page(res, 200, "로그인 완료", "차 화면을 확인해 주세요. 이 창은 닫아도 돼요.");
    }
    json(res, 404, { error: "not found" });
  } catch (e) {
    console.error(e);
    page(res, 500, "로그인에 실패했어요", "잠시 뒤 다시 시도해 주세요.");
  }
});

// 오래된 세션 정리
setInterval(() => { for (const [id, s] of sessions) if (!alive(s)) sessions.delete(id); }, 60 * 1000);

server.listen(PORT, () => console.log(`카카오 QR 로그인 서버: http://0.0.0.0:${PORT}  (Redirect URI: ${REDIRECT_URI})`));
