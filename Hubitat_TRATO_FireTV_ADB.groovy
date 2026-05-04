/**
 *  Hubitat - Amazon Fire TV / Firestick - ADB Driver
 *  Controla Fire TV diretamente via protocolo ADB TCP (sem servidor intermediário)
 *
 *  Copyright 2026 VH / TRATO  |  Apache 2.0
 *  1.5.2026 - Versão 1.1 -
 *
 *  SETUP INICIAL:
 *    1. Firestick → Settings → My Fire TV → Developer Options → ADB Debugging: ON
 *    2. Instale o driver, configure o IP e salve
 *    3. Clique em qualquer comando — a TV vai perguntar "Autorizar ADB?"
 *    4. Selecione "Sempre permitir" → pronto, autorizado para sempre
 */

import groovy.transform.Field

// ─── ADB Protocol Constants ───────────────────────────────────────────────────
@Field static final int CMD_CNXN = 0x4e584e43
@Field static final int CMD_AUTH = 0x48545541
@Field static final int CMD_OPEN = 0x4e45504f
@Field static final int CMD_OKAY = 0x59414b4f
@Field static final int CMD_CLSE = 0x45534c43
@Field static final int CMD_WRTE = 0x45545257

@Field static final int AUTH_TOKEN        = 1
@Field static final int AUTH_SIGNATURE    = 2
@Field static final int AUTH_RSAPUBLICKEY = 3

@Field static final int ADB_VERSION = 0x01000000
@Field static final int MAX_PAYLOAD = 4096
@Field static final int LOCAL_ID    = 1

// ─── Android Key Event Codes ─────────────────────────────────────────────────
@Field static final Map KEY = [
    HOME:3, BACK:4, MENU:82,
    DPAD_UP:19, DPAD_DOWN:20, DPAD_LEFT:21, DPAD_RIGHT:22, DPAD_CENTER:23,
    VOLUME_UP:24, VOLUME_DOWN:25, VOLUME_MUTE:164,
    POWER:26, WAKEUP:224, SLEEP:223,
    PLAY_PAUSE:85, STOP:86, NEXT:87, PREV:88,
    REWIND:89, FF:90, PLAY:126, PAUSE:127,
    ENTER:66, ESCAPE:111
]

// ─── App Package Names ────────────────────────────────────────────────────────
@Field static final Map APPS = [
    netflix: "com.netflix.ninja",
    prime:   "com.amazon.firebat",
    youtube: "com.amazon.firetv.youtube",
    disney:  "com.disney.disneyplus",
    hbo:     "com.hbo.hbonow",
    appletv: "com.apple.atve.amazon.appletv",
    hulu:    "com.hulu.plus",
    plex:    "com.plexapp.android",
    spotify: "com.spotify.music",
    twitch:  "tv.twitch.android.app"
]

// ─── RX Buffer estático (sobrevive entre callbacks parse() na mesma sessão) ───
@Field static final Map rxBuf = [:]

// ─────────────────────────────────────────────────────────────────────────────

metadata {
    definition(
        name:      "Amazon Fire TV (ADB)",
        namespace: "TRATO",
        author:    "VH"
    ) {
        capability "Switch"
        capability "Refresh"

        command "home"
        command "back"
        command "menu"
        command "wakeUp"
        command "sleepDevice"
        command "dpadUp"
        command "dpadDown"
        command "dpadLeft"
        command "dpadRight"
        command "select"
        command "volumeUp"
        command "volumeDown"
        command "mute"
        command "play"
        command "pause"
        command "playPause"
        command "stop"
        command "fastForward"
        command "rewind"
        command "nextTrack"
        command "previousTrack"
        command "launchNetflix"
        command "launchPrimeVideo"
        command "launchYouTube"
        command "launchDisneyPlus"
        command "launchHBOMax"
        command "launchAppleTV"
        command "launchApp",        [[name:"PackageName*", type:"STRING",
                                      description:"Package (ex: com.netflix.ninja)"]]
        command "sendKeyEvent",     [[name:"KeyCode*",     type:"NUMBER",
                                      description:"Android key code"]]
        command "sendShellCommand", [[name:"Command*",     type:"STRING",
                                      description:"ADB shell command"]]
        command "getCurrentApp"
        command "generateNewKey"
        command "disconnect"
        attribute "adbStatus",  "string"
        attribute "currentApp", "string"
    }

    preferences {
        input name: "ipAddress", type: "text",   title: "IP do Fire TV",  required: true
        input name: "adbPort",   type: "number", title: "Porta ADB",       defaultValue: 5555, required: true
        input name: "logEnable", type: "bool",   title: "Debug Logging",   defaultValue: true
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════════════

def installed() {
    log.info "[FireTV] Driver instalado"
    state.connState = "IDLE"
    sendEvent(name: "adbStatus",  value: "disconnected")
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "currentApp", value: "unknown")
    generateKeyPair()
}

def updated() {
    log.info "[FireTV] Configurações atualizadas"
    if (!state.adbPublicKey || !state.adbKeyD) generateKeyPair()
}

def uninstalled() {
    closeSocket()
}

def initialize() {
    state.connState  = "IDLE"
    rxBuf[device.id] = ""
    if (!state.adbPublicKey) generateKeyPair()
    sendEvent(name: "adbStatus", value: "disconnected")
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPERS — LITTLE-ENDIAN (sem java.nio)
// ═══════════════════════════════════════════════════════════════════════════════

private byte[] int32LE(long val) {
    return [
        (byte)( val        & 0xFF),
        (byte)((val >>  8) & 0xFF),
        (byte)((val >> 16) & 0xFF),
        (byte)((val >> 24) & 0xFF)
    ] as byte[]
}

// Concatena múltiplos byte arrays (o operador + não funciona no sandbox do Hubitat)
private byte[] concatBytes(List<byte[]> arrays) {
    int total = 0
    for (byte[] a : arrays) { if (a) total += a.length }
    byte[] result = new byte[total]
    int pos = 0
    for (byte[] a : arrays) {
        if (a) { for (int i = 0; i < a.length; i++) { result[pos++] = a[i] } }
    }
    return result
}

private long readInt32LE(byte[] buf, int offset) {
    return ((buf[offset]     & 0xFFL)      ) |
           ((buf[offset + 1] & 0xFFL) <<  8) |
           ((buf[offset + 2] & 0xFFL) << 16) |
           ((buf[offset + 3] & 0xFFL) << 24)
}

// ═══════════════════════════════════════════════════════════════════════════════
// RSA KEY — gerado via BigInteger puro (sem java.security.KeyPairGenerator)
// ═══════════════════════════════════════════════════════════════════════════════

def generateKeyPair() {
    log.info "[FireTV] Gerando chave RSA 2048-bit via BigInteger..."
    try {
        def rng = new java.util.Random(now())
        BigInteger p = new BigInteger(1024, 64, rng)
        BigInteger q = new BigInteger(1024, 64, rng)
        while (q == p) { q = new BigInteger(1024, 64, rng) }

        BigInteger n   = p.multiply(q)
        BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE))
        BigInteger e   = BigInteger.valueOf(65537L)
        BigInteger d   = e.modInverse(phi)

        // Salva chave pública (N e exponent) e privada (d) em state
        state.adbKeyN      = n.toString(16)
        state.adbKeyD      = d.toString(16)
        state.adbPublicKey = buildAdbPublicKey(n, 65537)

        log.info "[FireTV] Chave gerada. Na 1ª conexão, autorize na tela da TV."
    } catch (Exception ex) {
        log.error "[FireTV] Falha ao gerar chave: ${ex.message}"
    }
}

def generateNewKey() {
    state.adbPublicKey = null
    generateKeyPair()
    sendEvent(name: "adbStatus", value: "nova_chave_gerada")
    log.warn "[FireTV] Nova chave gerada — autorize novamente na TV."
}

// Constrói o formato binário da chave pública do Android ADB (528 bytes → base64)
private String buildAdbPublicKey(BigInteger n, int e) {
    int        w   = 64   // 2048 / 32 = 64 words
    BigInteger b32 = BigInteger.TWO.pow(32)

    // Constante de Montgomery: -n0^{-1} mod 2^32
    BigInteger n0inv = n.mod(b32).modInverse(b32).negate().mod(b32)

    // R^2 mod N com modPow eficiente (R = 2^2048)
    BigInteger rr = BigInteger.TWO.modPow(BigInteger.valueOf(4096L), n)

    // Estrutura: len(4) + n0inv(4) + n[256] + rr[256] + exponent(4) = 528 bytes
    byte[] buf = concatBytes([int32LE(w), int32LE(n0inv.longValue()),
                               bigIntToLE(n, w * 4), bigIntToLE(rr, w * 4), int32LE(e)])

    return "${buf.encodeBase64().toString().replaceAll("\\s", "")} hubitat_firetv\0"
}

// BigInteger (big-endian) → byte array little-endian de tamanho fixo
private byte[] bigIntToLE(BigInteger val, int size) {
    byte[] be = val.toByteArray()
    if (be.length > size && be[0] == (byte) 0) {
        be = be[1..-1] as byte[]
    }
    byte[] result = new byte[size]
    int copy = Math.min(be.length, size)
    for (int i = 0; i < copy; i++) {
        result[i] = be[be.length - 1 - i]
    }
    return result
}

// BigInteger → byte array big-endian de tamanho fixo (para saída de assinatura RSA)
private byte[] bigIntToFixedBytes(BigInteger val, int size) {
    byte[] bytes = val.toByteArray()
    if (bytes.length > size && bytes[0] == (byte) 0) {
        byte[] trimmed = new byte[bytes.length - 1]
        for (int i = 0; i < trimmed.length; i++) trimmed[i] = bytes[i + 1]
        bytes = trimmed
    }
    if (bytes.length == size) return bytes
    byte[] result = new byte[size]
    int offset = size - bytes.length
    for (int i = 0; i < bytes.length; i++) result[offset + i] = bytes[i]
    return result
}

// Assina o challenge token ADB com PKCS#1 v1.5 SHA-1 e a chave privada armazenada
private byte[] signWithPrivateKey(byte[] token) {
    try {
        if (!state.adbKeyD || !state.adbKeyN) {
            log.warn "[FireTV] Chave privada não disponível"
            return null
        }
        BigInteger d = new BigInteger(state.adbKeyD as String, 16)
        BigInteger n = new BigInteger(state.adbKeyN as String, 16)

        // SHA-1 do token de desafio
        byte[] sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(token)

        // PKCS#1 v1.5 DigestInfo header para SHA-1
        byte[] digestInfo = [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                             0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14] as byte[]

        // EM = 0x00 || 0x01 || PS (0xFF...) || 0x00 || DigestInfo || SHA1
        int keySize = 256  // 2048-bit / 8 bytes
        int psLen   = keySize - sha1.length - digestInfo.length - 3
        byte[] em   = new byte[keySize]
        em[0] = 0x00
        em[1] = 0x01
        for (int i = 0; i < psLen; i++) em[2 + i] = (byte) 0xFF
        em[2 + psLen] = 0x00
        for (int i = 0; i < digestInfo.length; i++) em[3 + psLen + i] = digestInfo[i]
        for (int i = 0; i < sha1.length; i++) em[3 + psLen + digestInfo.length + i] = sha1[i]

        // RSA: assinatura = em^d mod n
        BigInteger m   = new BigInteger(1, em)
        BigInteger sig = m.modPow(d, n)

        logD "Assinatura RSA calculada (${keySize} bytes)"
        return bigIntToFixedBytes(sig, keySize)
    } catch (Exception ex) {
        log.error "[FireTV] Erro ao assinar token: ${ex.message}"
        return null
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONEXÃO TCP / SOCKET
// ═══════════════════════════════════════════════════════════════════════════════

private void connectToDevice() {
    if (state.connState != "IDLE") {
        logD "Já conectando (${state.connState})"
        return
    }
    if (!settings.ipAddress) {
        log.error "[FireTV] IP não configurado"
        return
    }
    if (!state.adbPublicKey) {
        log.warn "[FireTV] Sem chave pública, gerando..."
        generateKeyPair()
        if (!state.adbPublicKey) {
            log.error "[FireTV] Falha ao obter chave pública"
            return
        }
    }

    logD "Conectando a ${settings.ipAddress}:${settings.adbPort}"
    state.connState  = "CONNECTING"
    state.remoteId   = 0
    rxBuf[device.id] = ""

    try {
        interfaces.rawSocket.connect(
            settings.ipAddress,
            settings.adbPort as Integer,
            byteInterface: true,
            timeout: 5000
        )
        sendEvent(name: "adbStatus", value: "conectando")
        pauseExecution(200)
        state.connState = "AUTH_WAIT"
        sendAdbConnect()
    } catch (Exception e) {
        log.error "[FireTV] Falha TCP: ${e.message}"
        state.connState = "IDLE"
        sendEvent(name: "adbStatus", value: "erro_conexao")
    }
}

private void closeSocket() {
    try { interfaces.rawSocket.close() } catch (e) { /* ignora */ }
    state.connState   = "IDLE"
    state.remoteId    = 0
    state.promptCount = 0
    sendEvent(name: "adbStatus", value: "desconectado")
}

def disconnect() {
    logD "Desconectando"
    closeSocket()
}

def socketStatus(String message) {
    logD "Socket: ${message}"
    if (message.contains("CLOSED") || message.contains("ERROR") || message.contains("error")) {
        log.warn "[FireTV] Socket fechado: ${message}"
        unschedule("forceCloseShell")
        state.connState   = "IDLE"
        state.remoteId    = 0
        state.promptCount = 0
        sendEvent(name: "adbStatus", value: "desconectado")
        // Reconecta automaticamente se havia comando pendente
        if (state.pendingShellCmd) {
            runIn(2, "reconnectPending")
        }
    }
}

def reconnectPending() {
    if (state.connState == "IDLE" && state.pendingShellCmd) {
        logD "Reconectando para executar comando pendente"
        connectToDevice()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROTOCOLO ADB — ENVIO
// ═══════════════════════════════════════════════════════════════════════════════

private void sendAdbConnect() {
    sendAdbMsg(CMD_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\0".bytes)
    logD "→ CNXN"
}

private void sendAdbMsg(int cmd, int arg0, int arg1, byte[] data) {
    int  len   = data?.length ?: 0
    long crc   = calcCRC32(data ?: new byte[0])
    long magic = (cmd ^ 0xffffffff) & 0xFFFFFFFFL

    byte[] header = concatBytes([int32LE(cmd), int32LE(arg0), int32LE(arg1),
                                  int32LE(len), int32LE(crc),  int32LE(magic)])
    byte[] msg = (len > 0) ? concatBytes([header, data]) : header

    sendRawHex(msg.encodeHex().toString().toUpperCase())
}

private long calcCRC32(byte[] data) {
    if (!data || data.length == 0) return 0L
    long crc = 0xFFFFFFFFL
    for (byte b : data) {
        crc ^= (b & 0xFFL)
        for (int i = 0; i < 8; i++) {
            crc = (crc & 1L) ? ((crc >>> 1) ^ 0xEDB88320L) : (crc >>> 1)
        }
    }
    return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL
}

private void sendRawHex(String hexStr) {
    try {
        interfaces.rawSocket.sendMessage(hexStr)
    } catch (Exception e) {
        log.error "[FireTV] Erro ao enviar: ${e.message}"
        state.connState = "IDLE"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROTOCOLO ADB — RECEPÇÃO
// ═══════════════════════════════════════════════════════════════════════════════

def parse(String message) {
    String key = device.id as String
    rxBuf[key] = (rxBuf[key] ?: "") + message.toUpperCase()

    if (rxBuf[key].length() > 131072) {
        log.warn "[FireTV] Buffer overflow, limpando"
        rxBuf[key] = ""
        return
    }

    while (rxBuf[key].length() >= 48) {
        byte[] hdr = rxBuf[key].substring(0, 48).decodeHex()

        int cmd     = (int) readInt32LE(hdr,  0)
        int arg0    = (int) readInt32LE(hdr,  4)
        int arg1    = (int) readInt32LE(hdr,  8)
        int dataLen = (int) readInt32LE(hdr, 12)

        int totalHex = 48 + (dataLen * 2)
        if (rxBuf[key].length() < totalHex) break

        byte[] data = (dataLen > 0)
            ? rxBuf[key].substring(48, totalHex).decodeHex()
            : new byte[0]

        rxBuf[key] = rxBuf[key].substring(totalHex)
        handleAdbMessage(cmd, arg0, arg1, data)
    }
}

private void handleAdbMessage(int cmd, int arg0, int arg1, byte[] data) {
    switch (cmd) {

        case CMD_CNXN:
            logD "← CNXN: autenticado"
            state.connState = "CONNECTED"
            sendEvent(name: "adbStatus", value: "conectado")
            executePendingShell()
            break

        case CMD_AUTH:
            if (arg0 == AUTH_TOKEN) {
                if (state.connState == "AUTH_WAIT") {
                    // 1ª tentativa: assinar o token com a chave privada
                    // Se a chave já for confiável, a TV responde com CNXN (sem diálogo)
                    logD "← AUTH TOKEN → tentando assinatura RSA"
                    state.connState = "AUTH_PUBKEY_WAIT"
                    byte[] sig = signWithPrivateKey(data)
                    if (sig) {
                        sendAdbMsg(CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                        logD "→ AUTH SIGNATURE"
                    } else {
                        // Sem chave privada: vai direto para chave pública
                        sendEvent(name: "adbStatus", value: "aguardando_autorizacao")
                        log.info "[FireTV] Selecione 'Sempre permitir' na tela da TV"
                        sendAdbMsg(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, state.adbPublicKey.bytes)
                        logD "→ AUTH RSAPUBLICKEY (sem chave privada)"
                    }
                } else {
                    // Assinatura rejeitada → chave não reconhecida → enviar chave pública
                    // A TV mostrará o diálogo "Autorizar ADB?" somente desta vez
                    logD "← AUTH TOKEN (assinatura rejeitada) → enviando chave pública"
                    sendEvent(name: "adbStatus", value: "aguardando_autorizacao")
                    log.info "[FireTV] Selecione 'Sempre permitir' na tela da TV"
                    sendAdbMsg(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, state.adbPublicKey.bytes)
                    logD "→ AUTH RSAPUBLICKEY"
                }
            }
            break

        case CMD_OKAY:
            if (state.connState == "SHELL_OPENING") {
                state.remoteId   = arg0
                state.connState  = "SHELL_READY"
                state.promptCount = 0
                logD "← OKAY shell aberto (remoteId=${state.remoteId})"
                String toSend = state.pendingShellCmd
                if (toSend) {
                    state.pendingShellCmd = null
                    sendAdbMsg(CMD_WRTE, LOCAL_ID, state.remoteId as int, (toSend + "\n").bytes)
                    logD "→ WRTE: ${toSend}"
                }
            } else if (state.connState == "SHELL_READY") {
                logD "← OKAY (flow ctrl)"
            }
            break

        case CMD_WRTE:
            sendAdbMsg(CMD_OKAY, LOCAL_ID, arg0, new byte[0])
            if (data && data.length > 0) {
                String resp = new String(data).replaceAll(/[\x00-\x08\x0b-\x1f]/, "").trim()
                if (resp) {
                    logD "← data: ${resp.take(200)}"
                    if (state.awaitCurrentApp && resp.contains("mCurrentFocus")) {
                        state.awaitCurrentApp = false
                        def m = (resp =~ /\{[^}]*\s+([\w.]+)\/([\w.]+)\}/)
                        if (m) {
                            sendEvent(name: "currentApp", value: m[0][1])
                            log.info "[FireTV] App atual: ${m[0][1]}"
                        }
                    }
                    // Detecta prompt do shell ($ ou #) e conta ocorrências:
                    // 1ª = prompt inicial (antes do comando), 2ª = após execução → fecha
                    if (state.connState == "SHELL_READY" &&
                            (resp.endsWith('$') || resp.endsWith('#'))) {
                        state.promptCount = (state.promptCount ?: 0) + 1
                        logD "Prompt #${state.promptCount}"
                        if (state.promptCount >= 2) {
                            logD "→ Comando concluído, fechando shell"
                            state.connState = "SHELL_CLOSING"
                            sendAdbMsg(CMD_CLSE, LOCAL_ID, state.remoteId as int, new byte[0])
                        }
                    }
                }
            }
            break

        case CMD_CLSE:
            logD "← CLSE (shell fechado)"
            if (state.connState != "SHELL_CLOSING") {
                sendAdbMsg(CMD_CLSE, LOCAL_ID, arg0, new byte[0])
            }
            state.remoteId    = 0
            state.promptCount = 0
            unschedule("forceCloseShell")
            // Mantém a conexão TCP viva — só autentica uma vez
            state.connState   = "CONNECTED"
            // Executa comando que chegou enquanto o shell estava ocupado
            if (state.pendingShellCmd) {
                executePendingShell()
            }
            break

        default:
            logD "← cmd desconhecido: 0x${Integer.toHexString(cmd & 0xFFFFFFFF)}"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXECUÇÃO DE SHELL
// ═══════════════════════════════════════════════════════════════════════════════

private void executePendingShell() {
    if (!state.pendingShellCmd) return
    logD "→ OPEN shell: (cmd: ${state.pendingShellCmd})"
    state.connState   = "SHELL_OPENING"
    state.promptCount = 0
    sendAdbMsg(CMD_OPEN, LOCAL_ID, 0, "shell:\0".bytes)
    runIn(5, "forceCloseShell")   // fallback: fecha se trava
}

def forceCloseShell() {
    if (state.connState in ["SHELL_OPENING", "SHELL_READY", "SHELL_CLOSING"]) {
        log.warn "[FireTV] Timeout: forçando fechamento do socket"
        state.pendingShellCmd = null   // descarta para não loop infinito
        closeSocket()
    }
}

def sendShell(String shellCmd) {
    logD "shell: ${shellCmd}"
    state.pendingShellCmd = shellCmd
    switch (state.connState) {
        case "IDLE":
            connectToDevice()
            break
        case "CONNECTED":
            executePendingShell()
            break
        case "SHELL_OPENING":
        case "SHELL_READY":
        case "SHELL_CLOSING":
            // Shell ocupado: comando ficará em fila e será executado após CMD_CLSE
            logD "Shell ocupado (${state.connState}), na fila"
            break
        default:
            logD "Conectando (${state.connState}), na fila"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CAPABILITIES
// ═══════════════════════════════════════════════════════════════════════════════

def on()      { wakeUp();      sendEvent(name: "switch", value: "on") }
def off()     { sleepDevice(); sendEvent(name: "switch", value: "off") }
def refresh() { getCurrentApp() }

// ─── Key Events ───────────────────────────────────────────────────────────────
def home()          { keyEvent(KEY.HOME) }
def back()          { keyEvent(KEY.BACK) }
def menu()          { keyEvent(KEY.MENU) }
def wakeUp()        { keyEvent(KEY.WAKEUP) }
def sleepDevice()   { keyEvent(KEY.SLEEP) }
def select()        { keyEvent(KEY.DPAD_CENTER) }
def dpadUp()        { keyEvent(KEY.DPAD_UP) }
def dpadDown()      { keyEvent(KEY.DPAD_DOWN) }
def dpadLeft()      { keyEvent(KEY.DPAD_LEFT) }
def dpadRight()     { keyEvent(KEY.DPAD_RIGHT) }
def volumeUp()      { keyEvent(KEY.VOLUME_UP) }
def volumeDown()    { keyEvent(KEY.VOLUME_DOWN) }
def mute()          { keyEvent(KEY.VOLUME_MUTE) }
def play()          { keyEvent(KEY.PLAY) }
def pause()         { keyEvent(KEY.PAUSE) }
def playPause()     { keyEvent(KEY.PLAY_PAUSE) }
def stop()          { keyEvent(KEY.STOP) }
def fastForward()   { keyEvent(KEY.FF) }
def rewind()        { keyEvent(KEY.REWIND) }
def nextTrack()     { keyEvent(KEY.NEXT) }
def previousTrack() { keyEvent(KEY.PREV) }

def keyEvent(int code)  { sendShell("input keyevent ${code}") }
def sendKeyEvent(code)  { keyEvent(code as int) }

// ─── Apps ─────────────────────────────────────────────────────────────────────
def launchApp(String pkg)  { sendShell("monkey -p ${pkg} -c android.intent.category.LAUNCHER 1") }
def launchNetflix()        { launchApp(APPS.netflix) }
def launchYouTube()        { launchApp(APPS.youtube) }
def launchDisneyPlus()     { launchApp(APPS.disney) }

// LandingActivity não é exportada; usa deep link primevideo:// roteado pelo Android
// Prime Video (Fire TV usa LEANBACK_LAUNCHER + DeepLinkRoutingActivity)
def launchPrimeVideo() {
    sendShell("am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n com.amazon.firebat/com.amazon.firebatcore.deeplink.DeepLinkRoutingActivity")
}
def launchHBOMax()     { sendShell("am start -n com.hbo.hbonow/com.wbd.beam.BeamActivity") }
def launchAppleTV()    { sendShell("am start -n com.apple.atve.amazon.appletv/.MainActivity") }

// ─── Shell & Status ───────────────────────────────────────────────────────────
def sendShellCommand(String cmd) { sendShell(cmd) }

def getCurrentApp() {
    state.awaitCurrentApp = true
    sendShell("dumpsys window windows | grep -E mCurrentFocus")
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private void logD(String msg) {
    if (settings.logEnable) log.debug "[FireTV] ${msg}"
}
