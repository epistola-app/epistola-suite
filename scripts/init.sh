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

    # Extract host from SSH URL (e.g., git@github.com:user/repo.git -> github.com)
    local ssh_host
    ssh_host=$(echo "$remote_url" | sed -n 's/^git@\([^:]*\):.*/\1/p')

    # Get the identity file that SSH would use for this host
    local ssh_key
    ssh_key=$(ssh -G "$ssh_host" 2>/dev/null | grep "^identityfile " | head -1 | awk '{print $2}')

    # Expand ~ to $HOME
    ssh_key="${ssh_key/#\~/$HOME}"

    # Append .pub for the public key
    local ssh_pub_key="${ssh_key}.pub"

    if [[ ! -f "$ssh_pub_key" ]]; then
        echo ""
        echo "WARNING: Could not find SSH public key for $ssh_host"
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
asdf current
echo ""
echo "Git hooks configured (conventional commit validation)"
echo "Commit signing configured (SSH)"
