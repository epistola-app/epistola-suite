#!/usr/bin/env bash
set -euo pipefail

configure_shell() {
    local shell_name
    shell_name=$(basename "$SHELL")
    local asdf_source

    if [[ "$OSTYPE" == "darwin"* ]]; then
        asdf_source=". $(brew --prefix asdf)/libexec/asdf.sh"
    else
        asdf_source=". \$HOME/.asdf/asdf.sh"
    fi

    case "$shell_name" in
        zsh)
            if ! grep -q "asdf.sh" ~/.zshrc 2>/dev/null; then
                echo -e "\n# asdf version manager\n$asdf_source" >> ~/.zshrc
                echo "Added asdf to ~/.zshrc"
            fi
            ;;
        bash)
            local rc_file=~/.bashrc
            [[ "$OSTYPE" == "darwin"* ]] && rc_file=~/.bash_profile
            if ! grep -q "asdf.sh" "$rc_file" 2>/dev/null; then
                echo -e "\n# asdf version manager\n$asdf_source" >> "$rc_file"
                echo "Added asdf to $rc_file"
            fi
            ;;
        *)
            echo "Unknown shell: $shell_name. Please add asdf to your shell config manually."
            ;;
    esac

    # Source asdf for current session
    eval "$asdf_source"
}

if ! command -v asdf >/dev/null; then
    echo "asdf is not installed."
    echo "Please install it first: https://asdf-vm.com/guide/getting-started.html"
    exit 1
fi

# Configure shell if not already done
configure_shell

# Install required plugins (idempotent)
asdf plugin add java    >/dev/null 2>&1 || true
asdf plugin add nodejs  >/dev/null 2>&1 || true

# Install versions from .tool-versions
asdf install

# Ensure shims are updated
asdf reshim
echo "Done. Active tools:"
asdf current
