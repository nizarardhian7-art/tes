package com.termux.app.quickcommands;

import java.util.ArrayList;
import java.util.List;

public class QuickCommandDefaults {

    public static List<QuickCommand> get() {
        List<QuickCommand> list = new ArrayList<>();

        // 1. UI & Aesthetics (Cool CLI Hacks)
        add(list, "UI & Aesthetics", "Install Starship Prompt (Modern CLI)", "pkg install -y starship && echo 'eval \"$(starship init bash)\"' >> ~/.bashrc");
        add(list, "UI & Aesthetics", "Install Neofetch (System Banner)", "pkg install -y neofetch && neofetch");
        add(list, "UI & Aesthetics", "Enable CLI Auto-Colors & Aliases", "cat << 'EOF' >> ~/.bashrc\nalias ls='ls --color=auto'\nalias grep='grep --color=auto'\nalias diff='diff --color=auto'\nexport PS1='\\[\\033[01;32m\\]\\u@termux\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '\nEOF");
        add(list, "UI & Aesthetics", "Install ZSH + Oh-My-Zsh", "pkg install -y zsh git && sh -c \"$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)\"");
        add(list, "UI & Aesthetics", "Hacker Screen (Cmatrix)", "pkg install -y cmatrix && cmatrix -b");

        // 2. Package Manager
        add(list, "Package Manager", "Update package list", "pkg update");
        add(list, "Package Manager", "Update & upgrade all", "pkg update && pkg upgrade -y");
        add(list, "Package Manager", "Install a package", "pkg install ");
        add(list, "Package Manager", "Search for a package", "pkg search ");
        add(list, "Package Manager", "List installed packages", "pkg list-installed");
        add(list, "Package Manager", "Clean package cache", "pkg clean && apt autoremove -y");
        add(list, "Package Manager", "Fix interrupted dpkg", "dpkg --configure -a");
        add(list, "Package Manager", "Change mirror/repo", "termux-change-repo");

        // 3. Developer Tools
        add(list, "Developer Tools", "Install Python 3 & Pip", "pkg install -y python");
        add(list, "Developer Tools", "Start Python REPL", "python3");
        add(list, "Developer Tools", "Create Python virtual env", "python3 -m venv venv && source venv/bin/activate");
        add(list, "Developer Tools", "Install Node.js (LTS)", "pkg install -y nodejs-lts");
        add(list, "Developer Tools", "Install C/C++ Build Essentials", "pkg install -y build-essential clang");
        add(list, "Developer Tools", "Install Git", "pkg install -y git");
        add(list, "Developer Tools", "Git status", "git status");
        add(list, "Developer Tools", "Git pretty log graph", "git log --oneline --graph --all");
        add(list, "Developer Tools", "Git pull latest", "git pull");

        // 4. Files & Storage
        add(list, "Files & Storage", "Setup phone storage symlinks", "termux-setup-storage");
        add(list, "Files & Storage", "Go to shared storage", "cd ~/storage/shared");
        add(list, "Files & Storage", "Go to home folder", "cd ~");
        add(list, "Files & Storage", "List files (detailed & human-readable)", "ls -la -h");
        add(list, "Files & Storage", "Show current directory", "pwd");
        add(list, "Files & Storage", "Find largest files/folders", "du -h -d 1 | sort -hr");
        add(list, "Files & Storage", "Disk free space", "df -h");
        add(list, "Files & Storage", "Extract .zip file", "unzip ");
        add(list, "Files & Storage", "Extract .tar.gz file", "tar -xzf ");

        // 5. Network & Utilities
        add(list, "Network & Utilities", "Get Public IP & Location Info", "curl -s https://ipinfo.io");
        add(list, "Network & Utilities", "Start local HTTP web server (Port 8080)", "python3 -m http.server 8080");
        add(list, "Network & Utilities", "Show open network ports & connections", "ss -tulpn");
        add(list, "Network & Utilities", "Test internet ping", "ping -c 4 google.com");
        add(list, "Network & Utilities", "Download file (follow redirects)", "curl -LO ");
        add(list, "Network & Utilities", "Install OpenSSH", "pkg install -y openssh");

        // 6. System & Diagnostics
        add(list, "System", "Termux system info", "termux-info");
        add(list, "System", "Check battery status", "termux-battery-status");
        add(list, "System", "Check WiFi info", "termux-wifi-connectioninfo");
        add(list, "System", "Show running processes (top)", "top");
        add(list, "System", "Who am I", "whoami");
        add(list, "System", "Kernel/OS info", "uname -a");
        add(list, "System", "Reload Termux settings", "termux-reload-settings");
        add(list, "System", "Clear screen", "clear");
        add(list, "System", "Exit session", "exit");

        return list;
    }

    private static void add(List<QuickCommand> list, String category, String label, String command) {
        list.add(new QuickCommand(category, label, command, false));
    }

}