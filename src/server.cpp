#include <iostream>
#include <string>
#include <vector>
#include <fstream>
#include <cstring>
#include <thread>
#include <mutex>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

const int PORT = 9999;
const std::string DB_FILE = "chat_db.txt";

std::vector<std::string> history;
std::mutex historyMtx;
std::vector<int> clients;
std::mutex clientsMtx;

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
    { std::lock_guard<std::mutex> lk(clientsMtx); clients.push_back(sock); }
    std::string acc;          // НОВОЕ: копилка для сборки длинных строк
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
    loadHistory();
    std::cout << "=== СЕРВЕР Ludarex v1.4 === строк: " << history.size() << "\n";
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
