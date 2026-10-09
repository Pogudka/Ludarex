#include <iostream>
#include <string>
#include <cstring>
#include <ctime>
#include <thread>
#include <vector>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <openssl/rand.h>

const int PORT = 9999;
int sock;
std::string pass, myName;

std::string currentTime() {
    std::time_t now = std::time(0);
    std::tm* t = std::localtime(&now);
    char buf[6];
    std::strftime(buf, sizeof(buf), "%H:%M", t);
    return std::string(buf);
}
std::string hex(const unsigned char* d, int n) {
    std::string s; char t[3];
    for (int i = 0; i < n; i++) { snprintf(t, 3, "%02x", d[i]); s += t; }
    return s;
}
std::vector<unsigned char> unhex(const std::string& s) {
    std::vector<unsigned char> v;
    for (size_t i = 0; i + 1 < s.size(); i += 2)
        v.push_back((unsigned char)strtol(s.substr(i, 2).c_str(), nullptr, 16));
    return v;
}
std::string enc(const std::string& text) {
    unsigned char key[32]; SHA256((const unsigned char*)pass.c_str(), pass.size(), key);
    unsigned char nonce[12]; RAND_bytes(nonce, 12);
    std::vector<unsigned char> out(text.size() + 64);
    int len = 0, total = 0;
    EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_EncryptInit_ex(c, nullptr, nullptr, key, nonce);
    EVP_EncryptUpdate(c, out.data(), &len, (const unsigned char*)text.c_str(), text.size());
    total = len;
    EVP_EncryptFinal_ex(c, out.data() + len, &len); total += len;
    unsigned char tag[16]; EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, tag);
    EVP_CIPHER_CTX_free(c);
    std::vector<unsigned char> p;
    p.insert(p.end(), nonce, nonce + 12);
    p.insert(p.end(), out.begin(), out.begin() + total);
    p.insert(p.end(), tag, tag + 16);
    return hex(p.data(), p.size());
}
std::string dec(const std::string& s) {
    std::vector<unsigned char> d = unhex(s);
    if (d.size() < 28) return "";
    unsigned char key[32]; SHA256((const unsigned char*)pass.c_str(), pass.size(), key);
    int ctlen = d.size() - 12 - 16;
    std::vector<unsigned char> out(ctlen + 32);
    int len = 0, total = 0;
    EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(c, EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_DecryptInit_ex(c, nullptr, nullptr, key, d.data());
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
std::string render(const std::string& line) {
    if (line.rfind("===", 0) == 0) return line;
    std::string d = dec(line);
    if (d.empty()) return line;
    size_t img = d.find("[IMG:");
    if (img != std::string::npos) return d.substr(0, img) + "[📷 фото]";
    return d;
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
            if (!line.empty()) std::cout << render(line) << "\n";
        }
    }
}
int main(int argc, char* argv[]) {
    std::string serverIP = "127.0.0.1";
    if (argc > 1) serverIP = argv[1];
    std::cout << "Твоё имя: "; std::getline(std::cin, myName);
    if (myName.empty()) myName = "Аноним";
    std::cout << "Секретный ключ (пусто = без шифрования): "; std::getline(std::cin, pass);

    sock = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET; addr.sin_port = htons(PORT);
    inet_pton(AF_INET, serverIP.c_str(), &addr.sin_addr);
    std::cout << "Звоню на " << serverIP << "...\n";
    if (connect(sock, (sockaddr*)&addr, sizeof(addr)) < 0) { std::cout << "Сервер не найден\n"; return 1; }
    std::thread(receiver).detach();

    std::string join = "=== " + myName + " зашёл в чат ===";
    sendLine(pass.empty() ? join : enc(join));

    std::cout << "Ты в чате! /exit - выйти\n";
    std::string input;
    while (true) {
        std::getline(std::cin, input);
        if (input == "/exit") break;
        if (input.empty()) continue;
        sendLine((!pass.empty() && input[0] != '/') ? enc("[" + currentTime() + "] [" + myName + "] " + input) : input);
    }
    close(sock);
    return 0;
}
