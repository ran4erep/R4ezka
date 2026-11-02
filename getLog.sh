#!/usr/bin/env bash
# ============================================================
#  getLog.sh — сбор диагностики региональной блокировки Rezka
#
#  КАК ПОЛЬЗОВАТЬСЯ (ВАЖНО):
#  1) БЕЗ VPN (проблемный кейс):
#        bash getLog.sh
#     По умолчанию опрашивается rezka-tv.org и проблемный
#     сериал (9332, «Закрытая школа»).
#
#  2) С VPN (эталон, рабочий кейс):
#        bash getLog.sh https://hdrezka-home.tv
#     (rezka-tv.org с VPN делает редирект на закрытое зеркало,
#      поэтому для эталона используем рабочий домен.)
#
#  Скрипт можно запускать из любой папки, результат ляжет в
#  ./rezka_logs/ рядом со скриптом:
#      log_<дата>.txt                 — сводный лог (весь вывод)
#      page_<дата>.html               — скачанная страница тайтла
#      page_meta_<дата>.json          — распарсенные id/переводчики/сезоны
#      cdn_<имя>_<дата>.json         — сырые ответы CDN AJAX
#      cdn_<имя>_headers_<дата>.txt  — HTTP-заголовки ответов CDN
#      streams_<имя>_<дата>.txt      — расшифрованные URL видеопотоков
#      probe_*                        — результаты опроса видеосерверов
#      geo_*.json                     — геолокация по внешнему IP
#
#  Требования: curl + python3 (в Termux уже есть; если нет:
#      pkg install curl python )
# ============================================================

set -u

# Позволяет Ctrl+C корректно завершить скрипт и всё равно напечатать "Done"
trap 'echo; echo "Прервано пользователем (Ctrl+C), но файлы собраны:"; ls -la "$OUT_DIR" 2>/dev/null | tail -n +2; echo "Done"; exit 0' INT TERM

# ---------- Настройки ----------
DEFAULT_BASE="https://rezka-tv.org"
DEFAULT_PAGE="/series/thriller/9332-zakrytaya-shkola-2011.html"

# Зеркало и страница: $1, $2 (опционально)
BASE="${1:-$DEFAULT_BASE}"
BASE="${BASE%/}"
PAGE="${2:-$DEFAULT_PAGE}"

TITLE_ID=$(printf '%s' "$PAGE" | sed -nE 's#.*/([0-9]+)[^/]*\.html.*#\1#p')
[ -z "$TITLE_ID" ] && TITLE_ID="9332"

UA="Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

# Выходной каталог — рядом со скриптом
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/rezka_logs"
TS="$(date +%Y%m%d_%H%M%S)"
LOG="$OUT_DIR/log_$TS.txt"

mkdir -p "$OUT_DIR"

# Весь вывод дублируется в консоль и в лог-файл
exec > >(tee -a "$LOG") 2>&1

# Сбрасываем прокси из окружения (чтобы случайно не утечь в VPN/прокси)
unset http_proxy https_proxy all_proxy no_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY 2>/dev/null || true

CURL_OPTS=(--noproxy '*' --compressed --connect-timeout 15 --max-time 35 \
    -b "$OUT_DIR/cookies_$TS.txt" -c "$OUT_DIR/cookies_$TS.txt")

WFORMAT='HTTP: %{http_code} | %{content_type} | %{size_download} B | ip: %{remote_ip} | %{http_version} | %{time_total}s | final: %{url_effective}\n'

echo "=============================================================="
echo " getLog.sh — диагностика региональной блокировки Rezka"
echo " Дата запуска: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo " Зеркало: $BASE"
echo " Тайтл:   $BASE$PAGE"
echo " Каталог логов: $OUT_DIR/"
echo " Сводный лог:   $LOG"
echo "--------------------------------------------------------------"
echo " НАПОМИНАНИЕ:"
echo "  • БЕЗ VPN  -> bash getLog.sh"
echo "  • С VPN    -> bash getLog.sh https://hdrezka-home.tv"
echo "=============================================================="
echo

# ---------- Проверка curl и python3 ----------
if ! command -v curl >/dev/null 2>&1; then
    echo "ОШИБКА: curl не найден. Установи: pkg install curl"
    exit 1
fi
echo "curl: $(curl --version | head -1)"
if ! command -v python3 >/dev/null 2>&1; then
    echo "ОШИБКА: python3 не найден (нужен для Anubis PoW и расшифровки). Установи: pkg install python"
    exit 1
fi
echo "python3: $(python3 --version 2>&1)"
echo

# ---------- 1. Внешний IP и геолокация ----------
echo "===== [1] Внешний IP и геолокация ====="
IP=$(curl -sS "${CURL_OPTS[@]}" --max-time 15 https://api.ipify.org || true)
echo "IP: ${IP:-не удалось определить}"
curl -sS "${CURL_OPTS[@]}" --max-time 15 https://ipinfo.io/json -o "$OUT_DIR/geo_ipinfo_$TS.json" 2>/dev/null
if [ -s "$OUT_DIR/geo_ipinfo_$TS.json" ]; then
    echo "Гео (ipinfo.io):"
    head -c 900 "$OUT_DIR/geo_ipinfo_$TS.json"; echo
else
    curl -sS "${CURL_OPTS[@]}" --max-time 15 https://ipapi.co/json/ -o "$OUT_DIR/geo_ipapi_$TS.json" 2>/dev/null
    if [ -s "$OUT_DIR/geo_ipapi_$TS.json" ]; then
        echo "Гео (ipapi.co):"
        head -c 900 "$OUT_DIR/geo_ipapi_$TS.json"; echo
    else
        echo "Гео: не удалось получить (сервисы гео недоступны)"
    fi
fi
echo

# ---------- 2. DNS (системный + DoH) ----------
echo "===== [2] DNS-резолв зеркала ====="
HOST=$(printf '%s' "$BASE" | sed -E 's#https?://##; s#/.*##')
echo "Хост: $HOST"
echo "--- системный резолв ---"
if command -v getent >/dev/null 2>&1; then
    getent ahostsv4 "$HOST" || echo "[!] getent: резолв не удался"
elif command -v nslookup >/dev/null 2>&1; then
    nslookup "$HOST" 2>/dev/null || echo "[!] nslookup: резолв не удался"
elif command -v dig >/dev/null 2>&1; then
    dig +short A "$HOST" || echo "[!] dig: резолв не удался"
else
    python3 - "$HOST" <<'PYEOF' || echo "[!] python: резолв не удался"
import sys, socket
try:
    for ip in socket.gethostbyname_ex(sys.argv[1])[2]:
        print(ip)
except Exception as e:
    print("[!]", e)
PYEOF
fi
echo "--- DoH (dns.google) — как приложение ---"
curl -sS "${CURL_OPTS[@]}" --max-time 15 \
    -H "accept: application/dns-json" \
    "https://dns.google/resolve?name=$HOST&type=A" \
    -o "$OUT_DIR/doh_$TS.json" 2>/dev/null
if [ -s "$OUT_DIR/doh_$TS.json" ]; then
    python3 - "$OUT_DIR/doh_$TS.json" <<'PYEOF'
import sys, json
try:
    d = json.load(open(sys.argv[1]))
    for a in d.get("Answer", []):
        if a.get("type") == 1:
            print("   DoH:", a.get("data"))
except Exception:
    print("   DoH: не удалось распарсить")
PYEOF
else
    echo "   DoH: нет ответа"
fi
echo

# ---------- 3. Доступность зеркала ----------
echo "===== [3] Доступность зеркала (HEAD) ====="
curl -sSI "${CURL_OPTS[@]}" -A "$UA" "$BASE/" 2>&1 | head -30
echo

# ---------- Помощник: решение Anubis PoW ----------
# Возвращает пару  nonce hash  в глобальные переменные ANUBIS_NONCE / ANUBIS_HASH.
ANUBIS_NONCE=""
ANUBIS_HASH=""
solve_anubis() {
    local html_file="$1"
    ANUBIS_NONCE=""
    ANUBIS_HASH=""

    local parsed
    if ! parsed=$(python3 - "$html_file" <<'PYEOF'
import sys, json
html = open(sys.argv[1], encoding='utf-8', errors='replace').read()
i = html.find('id="anubis_challenge"')
if i == -1:
    sys.exit(1)
j = html.find('{', i)
if j == -1:
    sys.exit(1)
k = html.find('</script>', j)
if k == -1:
    k = len(html)
try:
    d = json.loads(html[j:k])
    print(d['challenge']['randomData'])
    print(d['rules']['difficulty'])
except Exception:
    sys.exit(1)
PYEOF
); then
        echo "    [!!] Не удалось распарсить челлендж Anubis"
        return 1
    fi

    local randomData difficulty
    randomData=$(printf '%s\n' "$parsed" | sed -n '1p')
    difficulty=$(printf '%s\n' "$parsed" | sed -n '2p')
    [ -z "$randomData" ] && return 1

    read -r ANUBIS_NONCE ANUBIS_HASH < <(python3 - "$randomData" "$difficulty" <<'PYEOF'
import sys, hashlib
data = sys.argv[1].encode('utf-8')
difficulty = int(sys.argv[2])
full_zero_bytes = difficulty // 2
is_odd = difficulty % 2 == 1
nonce = 0
while True:
    nonce_str = str(nonce)
    h = hashlib.sha256(data + nonce_str.encode('utf-8')).digest()
    ok = all(b == 0 for b in h[:full_zero_bytes])
    if ok and is_odd:
        ok = (h[full_zero_bytes] & 0xF0) == 0
    if ok:
        print(nonce, h.hex())
        break
    nonce += 1
PYEOF
)
    [ -n "$ANUBIS_NONCE" ]
}

# ---------- Помощник: запрос с обходом Anubis ----------
# request_with_anubis <файл-ответ> <файл-заголовки> <curl args...>
request_with_anubis() {
    local body_file="$1"; shift
    local hdr_file="$1"; shift
    local tmp_body tmp_hdr pass_url pass_headers
    tmp_body="$(mktemp)"
    tmp_hdr="$(mktemp)"
    pass_headers="$(mktemp)"

    curl -sS "${CURL_OPTS[@]}" -A "$UA" -D "$tmp_hdr" -o "$tmp_body" \
        -w "$WFORMAT" "$@"

    if [ "$(head -c 2 "$tmp_body" | od -An -tx1 | tr -d ' \n')" = "1f8b" ]; then
        mv "$tmp_body" "$tmp_body.gz"
        gunzip -f "$tmp_body.gz" 2>/dev/null || mv "$tmp_body.gz" "$tmp_body"
    fi

    if grep -qi "anubis_challenge\|Проверяем, что вы не бот" "$tmp_body"; then
        echo "    -> обнаружен Anubis-челлендж, решаем PoW..."
        if solve_anubis "$tmp_body"; then
            local ch_id redir
            ch_id=$(grep -o '"id":"[^"]*"' "$tmp_body" | head -1 | cut -d'"' -f4)
            redir=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$PAGE")
            pass_url="$BASE/.within.website/x/cmd/anubis/api/pass-challenge?id=$ch_id&response=$ANUBIS_HASH&nonce=$ANUBIS_NONCE&redir=$redir&elapsedTime=100"
            curl -sS "${CURL_OPTS[@]}" -A "$UA" -H "Referer: $BASE$PAGE" \
                -D "$pass_headers" -o /dev/null \
                -w 'pass-challenge HTTP: %{http_code}\n' "$pass_url"
            curl -sS "${CURL_OPTS[@]}" -A "$UA" -D "$hdr_file" -o "$body_file" \
                -w "$WFORMAT" "$@"
        else
            echo "    [!!] Не удалось решить Anubis PoW"
            cp "$tmp_body" "$body_file"
            cp "$tmp_hdr" "$hdr_file"
        fi
    else
        cp "$tmp_body" "$body_file"
        cp "$tmp_hdr" "$hdr_file"
    fi
    rm -f "$tmp_body" "$tmp_hdr" "$pass_headers"
}

# ---------- 4. Страница тайтла + парсинг ----------
echo "===== [4] Страница тайтла ====="
request_with_anubis \
    "$OUT_DIR/page_$TS.html" \
    "$OUT_DIR/page_headers_$TS.txt" \
    -L \
    -H "Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7" \
    -H "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" \
    -H "Sec-Ch-Ua: \"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"" \
    -H "Sec-Ch-Ua-Mobile: ?1" \
    -H "Sec-Ch-Ua-Platform: \"Android\"" \
    -H "Sec-Fetch-Dest: document" \
    -H "Sec-Fetch-Mode: navigate" \
    -H "Sec-Fetch-Site: none" \
    -H "Sec-Fetch-User: ?1" \
    -H "Upgrade-Insecure-Requests: 1" \
    -w "$WFORMAT" \
    "$BASE$PAGE"
echo "--- первые 400 символов тела ---"
head -c 400 "$OUT_DIR/page_$TS.html"; echo
echo "--- признаки ---"
if grep -qi "anubis\|Проверяем, что вы не бот" "$OUT_DIR/page_$TS.html"; then
    echo ">> Страница всё ещё закрыта Anubis (не удалось пройти)"
fi
if grep -qi "недоступно.*регионе\|регионе.*недоступно\|недоступно в вашем регионе" "$OUT_DIR/page_$TS.html"; then
    echo ">> НАЙДЕН текст региональной блокировки на странице"
fi

# Парсинг id / переводчиков / сезонов (как приложение)
python3 - "$OUT_DIR/page_$TS.html" "$PAGE" <<'PYEOF' > "$OUT_DIR/page_meta_$TS.json"
import sys, re, json
html = open(sys.argv[1], encoding='utf-8', errors='replace').read()
page_url = sys.argv[2] if len(sys.argv) > 2 else ""
out = {"post_id": "", "is_series": False, "translators": [], "seasons": []}

m = (re.search(r'<input[^>]*id=["\']post_id["\'][^>]*value=["\'](\d+)["\']', html)
      or re.search(r'<input[^>]*value=["\'](\d+)["\'][^>]*id=["\']post_id["\']', html)
      or re.search(r'data-post_id=["\'](\d+)["\']', html)
      or re.search(r'data-id=["\'](\d+)["\']', html)
      or re.search(r'(?:sof\.tv\.)?initCDN(?:Series|Movies)Events\s*\(\s*["\']?(\d+)["\']?', html))
if m:
    out["post_id"] = m.group(1)

if re.search(r'initCDNSeriesEvents', html) or '/series/' in page_url:
    out["is_series"] = True

# Захватываем ЦЕЛЫЙ тег <li> (атрибуты могут идти в любом порядке, как в Jsoup)
pat_li = re.compile(r'(<li[^>]*>)(.*?)</li>', re.S)
def attr_int(tag, name):
    mm = re.search(r'data-(?:is_)?' + name + r'=["\'](\d+)', tag)
    return int(mm.group(1)) if mm else 0
seen = set()
for tag, body in pat_li.findall(html):
    if 'b-translator__item' not in tag:
        continue
    tm = re.search(r'data-translator_id=["\'](\d+)["\']', tag)
    if not tm or tm.group(1) in seen:
        continue
    tid = tm.group(1)
    text = re.sub(r'<[^>]+>', ' ', body)
    text = re.sub(r'\s+', ' ', text).strip()[:120]
    active = bool(re.search(r'class=["\'][^"\']*\bactive\b', tag)) or bool(re.search(r'\bactive\b', body[:100]))
    seen.add(tid)
    out["translators"].append({
        "id": tid, "name": text or "(без имени)",
        "default": active,
        "cam": attr_int(tag, "cam"), "ads": attr_int(tag, "ads"), "director": attr_int(tag, "director")
    })

# Если список переводчиков пуст — берём id из вызова инициализации плеера,
# как это делает приложение (initCDN(?:Series|Movies)Events(postId, translatorId, ...))
if not out["translators"]:
    im = re.search(r'(?:sof\.tv\.)?initCDN(?:Series|Movies)Events\s*\(\s*["\']?(\d+)["\']?\s*,\s*["\']?(\d+)["\']?', html)
    if im:
        out["translators"].append({
            "id": im.group(2), "name": "(из initCDN)",
            "default": True, "cam": 0, "ads": 0, "director": 0
        })
        if not out["post_id"]:
            out["post_id"] = im.group(1)

out["seasons"] = sorted(set(int(x) for x in re.findall(r'data-season_id=["\'](\d+)["\']', html)))
print(json.dumps(out, ensure_ascii=False))
PYEOF
META="$OUT_DIR/page_meta_$TS.json"
if [ -s "$META" ]; then
    echo "--- распарсено со страницы ---"
    python3 - "$META" <<'PYEOF'
import sys, json
d = json.load(open(sys.argv[1]))
print("  post_id:", d["post_id"] or "(не найден, берём из URL)")
print("  тип:", "сериал" if d["is_series"] else "фильм/не определён")
print("  сезоны в разметке:", d["seasons"] or "-")
print("  переводчики:")
for t in d["translators"]:
    flags = []
    if t["default"]: flags.append("default")
    if t["cam"]: flags.append("cam=%s" % t["cam"])
    if t["ads"]: flags.append("ads=%s" % t["ads"])
    if t["director"]: flags.append("director=%s" % t["director"])
    print("    id=%s | %s | %s" % (t["id"], t["name"], ",".join(flags) if flags else "-"))
if not d["translators"]:
    print("    (переводчики не найдены — используем fallback id=0)")
PYEOF
else
    echo "    (парсинг страницы не дал результата)"
fi
echo

# глобальные переменные
POST_ID=$(python3 -c 'import sys,json; d=json.load(open(sys.argv[1])); print(d["post_id"] or "")' "$META" 2>/dev/null || true)
[ -z "$POST_ID" ] && POST_ID="$TITLE_ID"
IS_SERIES=$(python3 -c 'import sys,json; print("1" if json.load(open(sys.argv[1]))["is_series"] else "0")' "$META" 2>/dev/null || echo "0")
REFERER="$BASE$PAGE"

# Составляем список переводчиков для опроса
eval "$(python3 - "$META" <<'PYEOF'
import sys, json
d = json.load(open(sys.argv[1]))
trs = d["translators"]
if not trs:
    trs = [{"id": "0", "name": "fallback", "default": True, "cam": 0, "ads": 0, "director": 0}]
# дефолтный первым
trs.sort(key=lambda t: 0 if t["default"] else 1)
lines = []
for i, t in enumerate(trs[:5]):
    lines.append("TR_%d_ID='%s'" % (i, t["id"]))
    lines.append("TR_%d_NAME='%s'" % (i, t["name"].replace("'", "")))
    lines.append("TR_%d_CAM=%d" % (i, t["cam"]))
    lines.append("TR_%d_ADS=%d" % (i, t["ads"]))
    lines.append("TR_%d_DIR=%d" % (i, t["director"]))
lines.append("TR_COUNT=%d" % min(len(trs), 5))
lines.append("SEASONS='%s'" % ",".join(str(s) for s in d["seasons"][:6]))
print("\n".join(lines))
PYEOF
)"

# ---------- Помощник: расшифровка URL и опрос видеопотока ----------
# probe_stream <quality> <url> <префикс>
PROBED_HOSTS=""
probe_stream() {
    local q="$1" url="$2" prefix="$3"
    local qq
    qq=$(printf '%s' "$q" | tr -c 'A-Za-z0-9' '_')
    local body="$OUT_DIR/probe_${prefix}_${qq}_body_$TS.bin"
    local hdr="$OUT_DIR/probe_${prefix}_${qq}_headers_$TS.txt"
    local host
    host=$(printf '%s' "$url" | sed -E 's#(https?://[^/]+).*#\1#')
    if printf '%s\n' "$PROBED_HOSTS" | grep -qxF "$host"; then
        echo "    [$q] пропускаем опрос (хост уже проверен): $url"
        return
    fi
    PROBED_HOSTS="$(printf '%s\n%s' "$PROBED_HOSTS" "$host")"

    local range=()
    if [[ "$url" != *.m3u8 ]]; then
        range=(-H "Range: bytes=0-2048")
    fi

    curl -sS -L "${CURL_OPTS[@]}" -A "$UA" \
        -H "Referer: $REFERER" \
        -H "Origin: $BASE" \
        -H "Accept: */*" \
        "${range[@]}" \
        -D "$hdr" -o "$body" \
        -w "    [$q] HTTP: %{http_code} | %{content_type} | %{size_download} B | ip: %{remote_ip} | final: %{url_effective}\n" \
        "$url" || echo "    [$q] сетевой сбой при опросе (соединение сброшено/закрыто)"
    echo "    заголовки:"
    sed 's/^/      /' "$hdr" | head -20
    echo "    маркеры (регион/блокировка):"
    grep -aiE 'К сожалению|регионе|регион|недоступно|Forbidden|Access denied|blocked|geo.?block|unauthorized' "$body" | head -5 | sed 's/^/      /' || true
    echo "    первые 400 символов тела:"
    head -c 400 "$body" | tr '\n' ' ' | sed 's/^/      /'; echo
}

# probe_streams <cdn json> <имя>
probe_streams() {
    local f="$1" name="$2"
    local out="$OUT_DIR/streams_${name}_$TS.txt"
    python3 - "$f" <<'PYEOF' > "$out"
import sys, re, base64, json

trash_chars = "@#!^$"
fixed = ["$$!!@$$", "//_//", "_//", "||", "@@", "##", "!!", "^^", "$$", "@#^$!", "!@#$^", "#h"]

def b64(s):
    p = base64.b64encode(s.encode()).decode()
    np = p.rstrip('=')
    return p, np

codes = set(fixed)
for c in trash_chars:
    p, np = b64(c); codes.add(p); codes.add(np)
for c1 in trash_chars:
    for c2 in trash_chars:
        s = c1 + c2; p, np = b64(s)
        codes.add(p); codes.add(np)
        codes.add(p.replace('+', '-').replace('/', '_'))
        codes.add(np.replace('+', '-').replace('/', '_'))
for c1 in trash_chars:
    for c2 in trash_chars:
        for c3 in trash_chars:
            s = c1 + c2 + c3; p, np = b64(s)
            codes.add(p); codes.add(np)
            codes.add(p.replace('+', '-').replace('/', '_'))
            codes.add(np.replace('+', '-').replace('/', '_'))
codes = sorted(codes, key=len, reverse=True)

def decrypt(data):
    data = data.strip()
    if not data:
        return ""
    if data.startswith("[") or data.startswith("http://") or data.startswith("https://"):
        return data
    while (data.startswith("#h") or data.startswith("//") or data.startswith("#")
           or data.startswith("$") or data.startswith("!") or data.startswith("@")
           or data.startswith("^") or data.startswith("_") or data.startswith("~")):
        if data.startswith("#h"):
            data = data[2:]
        elif data.startswith("//"):
            data = data[2:]
        else:
            data = data[1:]
    data = data.replace("\\/", "/")
    prev = -1
    it = 0
    while prev != len(data) and it < 3:
        prev = len(data)
        for code in codes:
            if code in data:
                data = data.replace(code, "")
        it += 1
    return try_decode(data)

def try_decode(raw):
    clean = raw.strip()
    if not clean:
        return ""
    if clean.startswith("[") or clean.startswith("http"):
        return clean
    clean = re.sub(r"[^A-Za-z0-9+/=\-_]", "", clean)
    clean = clean.replace("-", "+").replace("_", "/")
    r = len(clean) % 4
    if r == 2:
        clean += "=="
    elif r == 3:
        clean += "="
    elif r == 1:
        clean = clean[:-1]
    try:
        dec = base64.b64decode(clean).decode("utf-8", "replace").strip()
        if dec.startswith("#h") or (dec.startswith("aHR0c") and " " not in dec):
            return decrypt(dec)
        if "http" in dec or ".mp4" in dec or ".m3u8" in dec or "[" in dec:
            return dec
        return raw if "http" in raw else dec
    except Exception:
        return raw if "http" in raw else ""

raw = open(sys.argv[1], encoding="utf-8", errors="replace").read().strip()
url = ""
try:
    d = json.loads(raw)
    u = d.get("url")
    url = u if isinstance(u, str) else ""
except Exception:
    m = re.search(r'"url"\s*:\s*"([^"]+)"', raw)
    if m:
        url = m.group(1)

if not url or url in ("false", "null"):
    sys.exit(0)

url = url.replace("\\/", "/").replace("\\\"", "\"").replace("&quot;", "\"").replace("&amp;", "&")
decoded = decrypt(url)
qre = re.compile(r"\[\s*([^\]]+?)\s*\]([^\[]*)")
ure = re.compile(r"(?:https?:)?//[^\s,\"'<>]+")

seen_hosts = set()
n = 0
for m in qre.finditer(decoded):
    q = m.group(1).strip()
    if "ultra" in q.lower():
        continue
    for u in ure.findall(m.group(2)):
        if u.startswith("//"):
            u = "https:" + u
        if "ultra" in u.lower():
            continue
        host = re.sub(r"(https?://[^/]+).*", r"\1", u)
        if host in seen_hosts:
            continue
        seen_hosts.add(host)
        print(q + "\t" + u)
        n += 1
        if n >= 6:
            sys.exit(0)
if n == 0:
    # фоллбэк: просто все URL без тегов качества
    for u in ure.findall(decoded):
        if u.startswith("//"):
            u = "https:" + u
        if "ultra" in u.lower():
            continue
        host = re.sub(r"(https?://[^/]+).*", r"\1", u)
        if host in seen_hosts:
            continue
        seen_hosts.add(host)
        print("auto\t" + u)
        n += 1
        if n >= 6:
            break
PYEOF
    if [ -s "$out" ]; then
        echo "  расшифрованные потоки:"
        sed 's/^/    /' "$out"
        while IFS=$'\t' read -r q url; do
            [ -n "$url" ] || continue
            probe_stream "$q" "$url" "$name"
        done < "$out"
    else
        echo "  расшифровка не дала потоков (url:false/пусто?)"
    fi
}

# ---------- Помощник: CDN AJAX запрос ----------
cdn_try() {
    local name="$1"; shift
    local f="$OUT_DIR/cdn_${name}_$TS.json"
    local hf="$OUT_DIR/cdn_${name}_headers_$TS.txt"
    local ts
    ts="$(date +%s)"
    echo
    echo "===== [CDN] $name ====="
    request_with_anubis "$f" "$hf" \
        -H "X-Requested-With: XMLHttpRequest" \
        -H "Referer: $REFERER" \
        -H "Origin: $BASE" \
        -H "Accept: application/json, text/javascript, */*; q=0.01" \
        -H "Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7" \
        -H "Sec-Ch-Ua: \"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"" \
        -H "Sec-Ch-Ua-Mobile: ?1" \
        -H "Sec-Ch-Ua-Platform: \"Android\"" \
        -H "Sec-Fetch-Dest: empty" \
        -H "Sec-Fetch-Mode: cors" \
        -H "Sec-Fetch-Site: same-origin" \
        "$@" \
        "$BASE/ajax/get_cdn_series/?t=$ts"
    echo "--- тело (первые 600 символов) ---"
    head -c 600 "$f"; echo
    echo "--- ключевые поля ---"
    python3 - "$f" <<'PYEOF'
import sys, json, re
s = open(sys.argv[1], encoding='utf-8', errors='replace').read().strip()
if not s:
    print("  (пустой ответ)")
    sys.exit()
if s.lstrip().startswith('<'):
    print("  (HTML вместо JSON)")
    print("  содержит 'недоступно/регион':", bool(re.search(r'недоступно|регион|К сожалению', s, re.I)))
    sys.exit()
try:
    d = json.loads(s)
    print("  success:", d.get('success'))
    print("  message:", str(d.get('message'))[:200])
    u = d.get('url')
    print("  url присутствует:", u not in (None, False, '', 'false', 'null'))
    print("  url (первые 160):", str(u)[:160])
    print("  subtitle/subtitles:", bool(d.get('subtitle')) or bool(d.get('subtiles')))
except Exception:
    print("  url:false:", '"url":false' in s or '"url": null' in s)
    print("  'недоступно/регион':", bool(re.search(r'недоступно|регион|К сожалению', s, re.I)))
PYEOF
    # Для get_episodes ответ — это HTML сезонов/серий, а не видеопоток; опрос не нужен
    if [[ "$name" != episodes_* ]]; then
        probe_streams "$f" "$name"
    fi
    sleep 0.4
}

# ---------- 5. CDN AJAX: главные попытки ----------
echo
echo "//////////////////////////////////////////////////////////////"
echo "// 5. Опрос CDN AJAX (/ajax/get_cdn_series/)"
echo "//////////////////////////////////////////////////////////////"

# 5.0 Список сезонов/серий (как приложение: action=get_episodes)
if [ "$IS_SERIES" = "1" ]; then
    for ((i=0; i<TR_COUNT; i++)); do
        eval "tid=\$TR_${i}_ID"
        [ -n "$tid" ] || continue
        cdn_try "episodes_tr${tid}" \
            --data-urlencode "id=$POST_ID" \
            --data-urlencode "translator_id=$tid" \
            --data-urlencode "action=get_episodes"
    done
fi

# 5.1 get_stream по переводчикам (сезон 1, серия 1)
for ((i=0; i<TR_COUNT; i++)); do
    eval "tid=\$TR_${i}_ID"
    eval "tcam=\$TR_${i}_CAM"
    eval "tads=\$TR_${i}_ADS"
    eval "tdir=\$TR_${i}_DIR"
    [ -n "$tid" ] || continue
    cdn_try "stream_tr${tid}_s1_e1" \
        --data-urlencode "id=$POST_ID" \
        --data-urlencode "action=get_stream" \
        --data-urlencode "favs=0" \
        --data-urlencode "is_cam=$tcam" \
        --data-urlencode "is_ads=$tads" \
        --data-urlencode "is_director=$tdir" \
        --data-urlencode "translator_id=$tid" \
        --data-urlencode "season=1" \
        --data-urlencode "episode=1" \
        --data-urlencode "part=1"
done

# 5.2 Если на странице есть ещё сезоны — проверяем 2-й и 3-й (дефолтный переводчик)
DEFAULT_TR_ID=$(python3 -c 'import sys,json; d=json.load(open(sys.argv[1])); trs=d["translators"] or [{"id":"0"}]; trs.sort(key=lambda t: 0 if t.get("default") else 1); print(trs[0]["id"])' "$META" 2>/dev/null || echo "0")
for S in 2 3; do
    case ",$SEASONS," in
        *",$S,"*) cdn_try "stream_tr${DEFAULT_TR_ID}_s${S}_e1" \
                --data-urlencode "id=$POST_ID" \
                --data-urlencode "action=get_stream" \
                --data-urlencode "favs=0" \
                --data-urlencode "is_cam=0" \
                --data-urlencode "is_ads=0" \
                --data-urlencode "is_director=0" \
                --data-urlencode "translator_id=$DEFAULT_TR_ID" \
                --data-urlencode "season=$S" \
                --data-urlencode "episode=1" \
                --data-urlencode "part=1" ;;
    esac
done

# 5.3 get_movie (запасной вариант, как в приложении)
cdn_try "movie_tr0" \
    --data-urlencode "id=$POST_ID" \
    --data-urlencode "action=get_movie" \
    --data-urlencode "favs=0" \
    --data-urlencode "is_cam=0" \
    --data-urlencode "is_ads=0" \
    --data-urlencode "is_director=0" \
    --data-urlencode "translator_id=0"

# 5.4 Без translator_id (фоллбэк приложения)
if [ "$IS_SERIES" = "1" ]; then
    cdn_try "stream_noTr_s1_e1" \
        --data-urlencode "id=$POST_ID" \
        --data-urlencode "action=get_stream" \
        --data-urlencode "favs=0" \
        --data-urlencode "is_cam=0" \
        --data-urlencode "is_ads=0" \
        --data-urlencode "is_director=0" \
        --data-urlencode "season=1" \
        --data-urlencode "episode=1" \
        --data-urlencode "part=1"
fi

# ---------- 6. Полные заголовки первого CDN-ответа ----------
echo
echo "===== [6] Подробные заголовки первого CDN-ответа ====="
FIRST_CDN_HDR=""
for f in "$OUT_DIR"/cdn_stream_*headers_$TS.txt "$OUT_DIR"/cdn_movie_*headers_$TS.txt; do
    if [ -s "$f" ]; then FIRST_CDN_HDR="$f"; break; fi
done
if [ -n "$FIRST_CDN_HDR" ]; then
    cat "$FIRST_CDN_HDR"
else
    echo "Заголовки недоступны"
fi
echo

# ---------- 7. Итог ----------
echo "=============================================================="
echo " ГОТОВО. Файлы собраны в каталоге: $OUT_DIR/"
echo " Сводный лог: $LOG"
ls -la "$OUT_DIR" | tail -n +2
echo
echo "Для сравнения запусти второй прогон:"
echo "  С VPN: bash getLog.sh https://hdrezka-home.tv"
echo
echo "Done"
