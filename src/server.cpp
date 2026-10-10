#include <iostream>
#include <string>
#include <vector>
#include <fstream>
#include <cstring>
#include <ctime>
#include <thread>
#include <mutex>
#include <random>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

const int PORT = 9999;
const std::string DB_FILE = "chat_db.txt";
const std::string KEY_FILE = "master_key.txt";

std::vector<std::string> history;
std::mutex historyMtx;
std::vector<int> clients;
std::mutex clientsMtx;
std::string masterKey;

std::string utf8cp(long cp) {
    std::string s;
    s += (char)(0xF0 | (cp >> 18));
    s += (char)(0x80 | ((cp >> 12) & 0x3F));
    s += (char)(0x80 | ((cp >> 6) & 0x3F));
    s += (char)(0x80 | (cp & 0x3F));
    return s;
}
std::string genBabylon(int n) {
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<int> dist(0, 0x3FF);
    std::string s;
    for (int i = 0; i < n; i++) s += utf8cp(0x12000 + dist(gen));
    return s;
}
void loadOrCreateKey() {
    std::ifstream f(KEY_FILE);
    std::string line;
    if (std::getline(f, line) && !line.empty()) masterKey = line;
    else {
        masterKey = genBabylon(12);
        std::ofstream o(KEY_FILE);
        o << masterKey << "\n";
    }
}
void saveHistory() {
    std::ofstream f(DB_FILE);
    for (const std::string& s : history) f << s << "\n";
}
void loadHistory() {
    history.clear();
    std::ifstream f(DB_FILE);
    std::string s;
    while (std::getline(f, s)) if (!s.empty()) history.push_back(s);
}
void sendAll(const std::string& t) {
    std::lock_guard<std::mutex> lk(clientsMtx);
    for (int c : clients) send(c, t.c_str(), t.size(), 0);
}
void handleClient(int sock) {
    std::string kl = masterKey + "\n";
    send(sock, kl.c_str(), kl.size(), 0);
    { std::lock_guard<std::mutex> lk(clientsMtx); clients.push_back(sock); }
    std::string acc;
    char buf[8192];
    while (true) {
        int n = recv(sock, buf, sizeof(buf), 0);
        if (n <= 0) break;
        acc.append(buf, n);
        size_t p;
        while ((p = acc.find('\n')) != std::string::npos) {
            std::string req = acc.substr(0, p);
            acc.erase(0, p + 1);
            if (req.empty()) continue;
            if (req == "/history") {
                std::string reply;
                { std::lock_guard<std::mutex> lk(historyMtx);
                  for (const std::string& s : history) reply += s + "\n"; }
                if (reply.empty()) reply = "История пуста\n";
                send(sock, reply.c_str(), reply.size(), 0);
            } else if (req == "/clear") {
                { std::lock_guard<std::mutex> lk(historyMtx); history.clear(); saveHistory(); }
                sendAll("=== сервер очистил чат ===\n");
            } else {
                { std::lock_guard<std::mutex> lk(historyMtx); history.push_back(req); saveHistory(); }
                sendAll(req + "\n");
            }
        }
    }
    { std::lock_guard<std::mutex> lk(clientsMtx);
      for (size_t i = 0; i < clients.size(); i++) if (clients[i] == sock) { clients.erase(clients.begin() + i); break; } }
    close(sock);
}
int main() {
    loadOrCreateKey();
    loadHistory();
    std::cout << "=== СЕРВЕР Ludarex v1.5 ===\n";
    std::cout << "Мастер-ключ: " << masterKey << "\n";
    std::cout << "Строк в базе: " << history.size() << "\n";
    int ss = socket(AF_INET, SOCK_STREAM, 0);
    int yes = 1; setsockopt(ss, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in a{}; a.sin_family = AF_INET; a.sin_addr.s_addr = INADDR_ANY; a.sin_port = htons(PORT);
    if (bind(ss, (sockaddr*)&a, sizeof(a)) < 0) { std::cout << "Порт занят!\n"; return 1; }
    listen(ss, 5);
    std::cout << "[server] жду клиентов на порту " << PORT << "...\n";
    while (true) {
        int c = accept(ss, nullptr, nullptr);
        if (c < 0) continue;
        std::thread(handleClient, c).detach();
    }
    return 0;
}
