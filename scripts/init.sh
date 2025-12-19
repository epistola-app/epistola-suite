#!/usr/bin/env bash
set -euo pipefail

configure_shell() {
    local shell_name
    shell_name=$(basename "$SHELL")
    local mise_activate='eval "$(mise activate '"$shell_name"')"'

    case "$shell_name" in
        zsh)
            if ! grep -q "mise activate" ~/.zshrc 2>/dev/null; then
                echo -e "\n# mise version manager\n$mise_activate" >> ~/.zshrc
                echo "Added mise to ~/.zshrc"
            fi
            ;;
        bash)
            local rc_file=~/.bashrc
            [[ "$OSTYPE" == "darwin"* ]] && rc_file=~/.bash_profile
            if ! grep -q "mise activate" "$rc_file" 2>/dev/null; then
                echo -e "\n# mise version manager\n$mise_activate" >> "$rc_file"
                echo "Added mise to $rc_file"
            fi
            ;;
        *)
            echo "Unknown shell: $shell_name. Please add mise to your shell config manually."
            ;;
    esac
}

if ! command -v mise >/dev/null; then
    echo "mise is not installed."
    echo "Please install it first: https://mise.jdx.dev/getting-started.html"
    exit 1
fi

# Configure shell if not already done
configure_shell

# Install versions from .mise.toml
mise install

# Install npm dependencies for Git hooks (in .husky/)
echo "Setting up Git hooks..."
npm install --prefix .husky

# Configure Git to use .husky for hooks
git config core.hooksPath .husky

# Configure SSH commit signing
configure_commit_signing() {
    local remote_url
    remote_url=$(git remote get-url origin 2>/dev/null || echo "")

    # Only configure if using SSH remote
    if [[ ! "$remote_url" =~ ^git@ ]]; then
        echo ""
        echo "NOTE: Repository uses HTTPS remote. SSH commit signing not configured."
        echo "To enable signing, switch to SSH remote or configure manually."
        return
    fi

    # Detect the SSH key actually used for authentication by running git fetch with verbose SSH
    echo "Detecting SSH key used for authentication..."
    local ssh_key
    ssh_key=$(GIT_SSH_COMMAND="ssh -v" git fetch 2>&1 | grep "Server accepts key:" | head -1 | awk '{print $5}')

    if [[ -z "$ssh_key" ]]; then
        echo ""
        echo "WARNING: Could not detect SSH key used for GitHub authentication."
        echo "Commit signing not configured."
        return
    fi

    # Append .pub for the public key
    local ssh_pub_key="${ssh_key}.pub"

    if [[ ! -f "$ssh_pub_key" ]]; then
        echo ""
        echo "WARNING: Could not find public key: $ssh_pub_key"
        echo "Commit signing not configured."
        return
    fi

    # Check if already configured
    if git config --get commit.gpgsign >/dev/null 2>&1; then
        echo "Commit signing already configured"
        return
    fi

    echo "Configuring SSH commit signing..."
    git config gpg.format ssh
    git config user.signingkey "$ssh_pub_key"
    git config commit.gpgsign true

    echo "Commit signing enabled using: $ssh_pub_key"
    echo ""
    echo "IMPORTANT: Ensure your SSH key is added to GitHub as a signing key:"
    echo "  1. Go to GitHub → Settings → SSH and GPG keys"
    echo "  2. Click 'New SSH key', select 'Signing Key' type"
    echo "  3. Paste your public key (shown below):"
    echo ""
    cat "$ssh_pub_key"
    echo ""
}

configure_commit_signing

echo ""
echo "Done. Active tools:"
mise current
echo ""
echo "Git hooks configured (conventional commit validation)"
echo "Commit signing configured (SSH)"
echo ""
echo "NOTE: Restart your shell to activate mise."
