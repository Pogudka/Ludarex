#include <iostream>
#include <string>
#include <sstream>
#include <cstring>
#include <ctime>
#include <thread>
#include <vector>
#include <map>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/bn.h>
#include <openssl/rand.h>

const std::string MASTER_KEY = "ludarex-babylon-2026";
const int PORT = 9999;
int sock;
std::string myName;
std::string currentPeer;
std::map<std::string, std::vector<unsigned char>> sessionKeys;
std::map<std::string, std::string> pendingA, pendingP;

std::vector<unsigned char> groupKey() {
    std::vector<unsigned char> d(SHA256_DIGEST_LENGTH);
    SHA256((const unsigned char*)MASTER_KEY.c_str(), MASTER_KEY.size(), d.data());
    return d;
}
std::string hexs(const unsigned char* d, int n) {
    std::string s; char t[3];
    for (int i = 0; i < n; i++) { snprintf(t, 3, "%02x", d[i]); s += t; }
    return s;
}
std::vector<unsigned char> unhexs(const std::string& s) {
    std::vector<unsigned char> v;
    for (size_t i = 0; i + 1 < s.size(); i += 2)
        v.push_back((unsigned char)strtol(s.substr(i, 2).c_str(), nullptr, 16));
    return v;
}
std::string bn2hex(BIGNUM* b) {
    char* h = BN_bn2hex(b); std::string s(h); OPENSSL_free(h);
    for (auto& c : s) c = (char)tolower((unsigned char)c);
    return s;
}
BIGNUM* hex2bn(const std::string& h) { BIGNUM* b = nullptr; BN_hex2bn(&b, h.c_str()); return b; }
std::vector<unsigned char> sha256str(const std::string& s) {
    std::vector<unsigned char> d(SHA256_DIGEST_LENGTH);
    SHA256((const unsigned char*)s.c_str(), s.size(), d.data());
    return d;
}
std::string fingerprint(const std::vector<unsigned char>& key) {
    auto d = sha256str(std::string(key.begin(), key.end()));
    return hexs(d.data(), 4);
}
std::string enc(const std::vector<unsigned char>& key, const std::string& text) {
    unsigned char nonce[12]; RAND_bytes(nonce, 12);
    std::vector<unsigned char> out(text.size() + 64);
    int len = 0, total = 0;
    EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_EncryptInit_ex(c, nullptr, nullptr, key.data(), nonce);
    EVP_EncryptUpdate(c, out.data(), &len, (const unsigned char*)text.c_str(), text.size());
    total = len;
    EVP_EncryptFinal_ex(c, out.data() + len, &len); total += len;
    unsigned char tag[16]; EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, tag);
    EVP_CIPHER_CTX_free(c);
    std::vector<unsigned char> p;
    p.insert(p.end(), nonce, nonce + 12);
    p.insert(p.end(), out.begin(), out.begin() + total);
    p.insert(p.end(), tag, tag + 16);
    return hexs(p.data(), p.size());
}
std::string dec(const std::vector<unsigned char>& key, const std::string& s) {
    std::vector<unsigned char> d = unhexs(s);
    if (d.size() < 28) return "";
    int ctlen = d.size() - 12 - 16;
    std::vector<unsigned char> out(ctlen + 32);
    int len = 0, total = 0;
    EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_DecryptInit_ex(c, nullptr, nullptr, key.data(), d.data());
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, d.data() + 12 + ctlen);
    if (EVP_DecryptUpdate(c, out.data(), &len, d.data() + 12, ctlen) != 1) { EVP_CIPHER_CTX_free(c); return ""; }
    total = len;
    if (EVP_DecryptFinal_ex(c, out.data() + len, &len) != 1) { EVP_CIPHER_CTX_free(c); return ""; }
    total += len;
    EVP_CIPHER_CTX_free(c);
    return std::string((char*)out.data(), total);
}
void sendLine(const std::string& payload) {
    std::string p = payload + "\n";
    send(sock, p.c_str(), p.size(), 0);
}
std::string currentTime() {
    std::time_t now = std::time(0);
    std::tm* t = std::localtime(&now);
    char buf[6];
    std::strftime(buf, sizeof(buf), "%H:%M", t);
    return std::string(buf);
}
void show(const std::string& d) {
    size_t img = d.find("[IMG:");
    if (img != std::string::npos) std::cout << d.substr(0, img) << "[📷 фото]\n";
    else std::cout << d << "\n";
}
void handleDHREQ(const std::string& from, const std::string& pHex, const std::string& gHex, const std::string& AHex) {
    BN_CTX* ctx = BN_CTX_new();
    BIGNUM *p = hex2bn(pHex), *g = hex2bn(gHex), *A = hex2bn(AHex);
    BIGNUM *b = BN_new(), *B = BN_new(), *shared = BN_new();
    BN_rand(b, 256, -1, 0);
    BN_mod_exp(B, g, b, p, ctx);
    BN_mod_exp(shared, A, b, p, ctx);
    std::string sharedHex = bn2hex(shared);
    std::vector<unsigned char> key = sha256str(sharedHex);
    sessionKeys[from] = key;
    std::string Bhex = bn2hex(B);
    sendLine("@" + from + " DHRES " + myName + " " + Bhex);
    std::cout << "🔐 ключ с " << from << " установлен, отпечаток: " << fingerprint(key) << "\n";
    BN_free(p); BN_free(g); BN_free(A); BN_free(b); BN_free(B); BN_free(shared); BN_CTX_free(ctx);
}
void handleDHRES(const std::string& from, const std::string& BHex) {
    if (!pendingA.count(from)) return;
    BN_CTX* ctx = BN_CTX_new();
    BIGNUM *p = hex2bn(pendingP[from]), *B = hex2bn(BHex), *a = hex2bn(pendingA[from]), *shared = BN_new();
    BN_mod_exp(shared, B, a, p, ctx);
    std::string sharedHex = bn2hex(shared);
    std::vector<unsigned char> key = sha256str(sharedHex);
    sessionKeys[from] = key;
    std::cout << "🔐 ключ с " << from << " установлен, отпечаток: " << fingerprint(key) << "\n";
    pendingA.erase(from); pendingP.erase(from);
    BN_free(p); BN_free(B); BN_free(a); BN_free(shared); BN_CTX_free(ctx);
}
void receiver() {
    char buf[8192]; std::string acc;
    while (true) {
        memset(buf, 0, sizeof(buf));
        int n = recv(sock, buf, sizeof(buf) - 1, 0);
        if (n <= 0) { std::cout << "\n[связь потеряна]\n"; break; }
        acc.append(buf, n);
        size_t p;
        while ((p = acc.find('\n')) != std::string::npos) {
            std::string line = acc.substr(0, p);
            acc.erase(0, p + 1);
            if (line.empty()) continue;
            std::stringstream ss(line);
            std::string head; ss >> head;
            if (head == "DHREQ") {
                std::string from, pHex, gHex, AHex; ss >> from >> pHex >> gHex >> AHex;
                handleDHREQ(from, pHex, gHex, AHex);
            } else if (head == "DHRES") {
                std::string from, BHex; ss >> from >> BHex;
                handleDHRES(from, BHex);
            } else if (head == "DM") {
                std::string from, ct; ss >> from >> ct;
                if (!sessionKeys.count(from)) { std::cout << "🔒 нет ключа с " << from << "\n"; continue; }
                std::string d = dec(sessionKeys[from], ct);
                if (d.empty()) std::cout << "🔒 не читается от " << from << "\n";
                else show(d);
            } else if (line.rfind("===", 0) == 0) {
                std::cout << line << "\n";
            } else {
                std::string d = dec(groupKey(), line);
                if (d.empty()) std::cout << "🔒 не читается (len=" << line.size() << ")\n";
                else show(d);
            }
        }
    }
}
int main(int argc, char* argv[]) {
    std::string serverIP = "127.0.0.1";
    if (argc > 1) serverIP = argv[1];
    std::cout << "Твоё имя: "; std::getline(std::cin, myName);
    if (myName.empty()) myName = "Аноним";

    sock = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET; addr.sin_port = htons(PORT);
    inet_pton(AF_INET, serverIP.c_str(), &addr.sin_addr);
    std::cout << "Звоню на " << serverIP << "...\n";
    if (connect(sock, (sockaddr*)&addr, sizeof(addr)) < 0) { std::cout << "Сервер не найден\n"; return 1; }
    sendLine("REG " + myName);
    std::thread(receiver).detach();

    std::cout << "Команды: /dm ИМЯ - secret chat, /group - общий чат, /exit - выход\n";
    std::string input;
    while (true) {
        std::getline(std::cin, input);
        if (input == "/exit") break;
        if (input.empty()) continue;
        if (input == "/group") { currentPeer = ""; std::cout << "режим: общий чат\n"; continue; }
        if (input.rfind("/dm ", 0) == 0) {
            currentPeer = input.substr(4);
            BN_CTX* ctx = BN_CTX_new();
            BIGNUM *p = BN_new(), *g = BN_new(), *a = BN_new(), *A = BN_new();
            BN_generate_prime_ex(p, 1024, 1, nullptr, nullptr, nullptr);
            BN_set_word(g, 2);
            BN_rand(a, 256, -1, 0);
            BN_mod_exp(A, g, a, p, ctx);
            pendingA[currentPeer] = bn2hex(a);
            pendingP[currentPeer] = bn2hex(p);
            sendLine("@" + currentPeer + " DHREQ " + myName + " " + bn2hex(p) + " " + bn2hex(g) + " " + bn2hex(A));
            std::cout << "предложение ключа отправлено " << currentPeer << "...\n";
            BN_free(p); BN_free(g); BN_free(a); BN_free(A); BN_CTX_free(ctx);
            continue;
        }
        std::string body = "[" + currentTime() + "] [" + myName + "] " + input;
        if (!currentPeer.empty() && sessionKeys.count(currentPeer)) {
            std::string ct = enc(sessionKeys[currentPeer], body);
            sendLine("@" + currentPeer + " DM " + myName + " " + ct);
            show(body);
        } else {
            std::string ct = enc(groupKey(), body);
            sendLine(ct);
        }
    }
    close(sock);
    return 0;
}
