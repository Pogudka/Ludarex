#include <iostream>
#include <string>
#include <vector>
#include <fstream>
#include <ctime>
#include <cstring>
#include <thread>
#include <mutex>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

const int PORT = 9999;
const std::string DB_FILE = "chat_db.txt";

struct Message { std::string time; std::string text; };
std::vector<Message> history;
std::mutex historyMtx;

struct Client { int sock; std::string name; };
std::vector<Client> clients;
std::mutex clientsMtx;

std::string currentTime() {
    std::time_t now = std::time(0);
    std::tm* t = std::localtime(&now);
    char buf[6];
    std::strftime(buf, sizeof(buf), "%H:%M", t);
    return std::string(buf);
}

void saveHistory() {
    std::ofstream file(DB_FILE);
    for (const Message& m : history)
        file << m.time << "\n" << m.text << "\n";
}

void loadHistory() {
    history.clear();
    std::ifstream file(DB_FILE);
    if (!file.is_open()) return;
    std::string t, txt;
    while (std::getline(file, t) && std::getline(file, txt)) {
        Message m; m.time = t; m.text = txt;
        history.push_back(m);
    }
}

void sendAll(const std::string& text) {
    std::lock_guard<std::mutex> lk(clientsMtx);
    for (Client& c : clients)
        send(c.sock, text.c_str(), text.size(), 0);
}

void handleClient(int sock) {
    char buf[4096];
    memset(buf, 0, sizeof(buf));
    int n = recv(sock, buf, sizeof(buf) - 1, 0);
    if (n <= 0) { close(sock); return; }
    std::string name(buf);

    {
        std::lock_guard<std::mutex> lk(clientsMtx);
        clients.push_back({sock, name});
    }
    sendAll("=== " + name + " зашёл в чат ===\n");

    while (true) {
        memset(buf, 0, sizeof(buf));
        n = recv(sock, buf, sizeof(buf) - 1, 0);
        if (n <= 0) break;
        std::string request(buf);

        if (request == "/history") {
            std::string reply;
            std::lock_guard<std::mutex> lk(historyMtx);
            for (Message m : history)
                reply += "[" + m.time + "] " + m.text + "\n";
            if (reply.empty()) reply = "История пуста\n";
            send(sock, reply.c_str(), reply.size(), 0);
        } else {
            Message m;
            m.time = currentTime();
            m.text = "[" + name + "] " + request;
            {
                std::lock_guard<std::mutex> lk(historyMtx);
                history.push_back(m);
                saveHistory();
            }
            sendAll("[" + m.time + "] " + m.text + "\n");
        }
    }

    {
        std::lock_guard<std::mutex> lk(clientsMtx);
        for (size_t i = 0; i < clients.size(); i++)
            if (clients[i].sock == sock) { clients.erase(clients.begin() + i); break; }
    }
    sendAll("=== " + name + " вышел из чата ===\n");
    close(sock);
}

int main() {
    loadHistory();
    std::cout << "=== СЕРВЕР Ludarex v1.2 === история: "
              << history.size() << "\n";

    int serverSock = socket(AF_INET, SOCK_STREAM, 0);
    int yes = 1;
    setsockopt(serverSock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(PORT);
    if (bind(serverSock, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cout << "Порт занят!\n"; return 1;
    }
    listen(serverSock, 5);
    std::cout << "[server] жду клиентов на порту " << PORT << "...\n";

    while (true) {
        int clientSock = accept(serverSock, nullptr, nullptr);
        if (clientSock < 0) continue;
        std::thread(handleClient, clientSock).detach();
    }
    return 0;
}
