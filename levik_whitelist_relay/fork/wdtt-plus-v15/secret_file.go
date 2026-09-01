// SPDX-License-Identifier: GPL-3.0-only
// Levik VPN modification: secure file-only secret bootstrap.

package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const maxBootstrapSecretBytes = 4096

var errUnsafeSecretFile = errors.New("unsafe secret file")

// readOwnerOnlySecretFile opens path without following symlinks and rejects
// relative paths, non-regular files, permissive modes and empty content.
// systemd LoadCredential provides files with these properties without putting
// the secret in argv or the environment.
func readOwnerOnlySecretFile(path string) (string, error) {
	if !filepath.IsAbs(path) {
		return "", fmt.Errorf("%w: path must be absolute", errUnsafeSecretFile)
	}
	parent := filepath.Clean(filepath.Dir(path))
	canonicalParent, err := filepath.EvalSymlinks(parent)
	if err != nil || canonicalParent != parent {
		return "", fmt.Errorf("%w: parent path is not canonical", errUnsafeSecretFile)
	}
	parentInfo, err := os.Lstat(parent)
	if err != nil || !parentInfo.IsDir() || parentInfo.Mode().Perm()&0077 != 0 || !rootOwnedSecretPath(parentInfo, false) {
		return "", fmt.Errorf("%w: unsafe parent directory", errUnsafeSecretFile)
	}
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return "", fmt.Errorf("%w: open failed", errUnsafeSecretFile)
	}
	file := os.NewFile(uintptr(fd), path)
	if file == nil {
		_ = syscall.Close(fd)
		return "", errUnsafeSecretFile
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > maxBootstrapSecretBytes || !rootOwnedSecretPath(info, true) {
		return "", errUnsafeSecretFile
	}
	value, err := io.ReadAll(io.LimitReader(file, maxBootstrapSecretBytes+1))
	if err != nil || len(value) > maxBootstrapSecretBytes {
		return "", errUnsafeSecretFile
	}
	secret := strings.TrimSpace(string(value))
	if secret == "" {
		return "", errUnsafeSecretFile
	}
	return secret, nil
}

func rootOwnedSecretPath(info os.FileInfo, singleLink bool) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != 0 {
		return false
	}
	return !singleLink || stat.Nlink == 1
}

func chooseFileSecret(direct, path, name string, required bool) (string, error) {
	if strings.TrimSpace(direct) != "" {
		return "", fmt.Errorf("-%s is disabled in the Levik build; use -%s-file", name, name)
	}
	if strings.TrimSpace(path) == "" {
		if required {
			return "", fmt.Errorf("-%s-file is required", name)
		}
		return "", nil
	}
	return readOwnerOnlySecretFile(path)
}
