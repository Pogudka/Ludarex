#include <iostream>
#include <string>
#include <cstring>
#include <thread>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

const int PORT = 9999;
int sock;

void receiver() {
    char buf[8192];
    while (true) {
        memset(buf, 0, sizeof(buf));
        int n = recv(sock, buf, sizeof(buf) - 1, 0);
        if (n <= 0) { std::cout << "\n[связь потеряна]\n"; break; }
        std::cout << buf;
    }
}

int main(int argc, char* argv[]) {
    std::string serverIP = "127.0.0.1";
    if (argc > 1) serverIP = argv[1];

    std::string name;
    std::cout << "Твоё имя: ";
    std::getline(std::cin, name);
    if (name.empty()) name = "Аноним";

    sock = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(PORT);
    inet_pton(AF_INET, serverIP.c_str(), &addr.sin_addr);

    std::cout << "Звоню на " << serverIP << "...\n";
    if (connect(sock, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cout << "Сервер не найден\n"; return 1;
    }
    send(sock, name.c_str(), name.size(), 0);
    std::thread(receiver).detach();

    std::cout << "Ты в чате! /exit - выйти\n";
    std::string input;
    while (true) {
        std::getline(std::cin, input);
        if (input == "/exit") break;
        if (input.empty()) continue;
        send(sock, input.c_str(), input.size(), 0);
    }
    close(sock);
    std::cout << "Ты вышел из чата\n";
    return 0;
}
