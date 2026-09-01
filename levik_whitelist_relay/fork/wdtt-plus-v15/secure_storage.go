// SPDX-License-Identifier: GPL-3.0-only
// Levik hardening for credential and private-key persistence.

package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

const maxSecureDatabaseBytes = 64 << 20

var errUnsafeStorage = errors.New("unsafe credential storage")

func validateRootPrivateDirectory(directory string) error {
	if !filepath.IsAbs(directory) || filepath.Clean(directory) != directory {
		return fmt.Errorf("%w: directory must be canonical and absolute", errUnsafeStorage)
	}
	canonical, err := filepath.EvalSymlinks(directory)
	if err != nil || canonical != directory {
		return fmt.Errorf("%w: directory contains a symlink", errUnsafeStorage)
	}
	info, err := os.Lstat(directory)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 || !rootOwnedSecretPath(info, false) {
		return fmt.Errorf("%w: directory must be root-owned mode 0700", errUnsafeStorage)
	}
	return nil
}

func readRootPrivateFile(path string, maxBytes int64) ([]byte, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path || maxBytes <= 0 {
		return nil, errUnsafeStorage
	}
	if err := validateRootPrivateDirectory(filepath.Dir(path)); err != nil {
		return nil, err
	}
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), path)
	if file == nil {
		_ = syscall.Close(fd)
		return nil, errUnsafeStorage
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Size() > maxBytes || !rootOwnedSecretPath(info, true) {
		return nil, errUnsafeStorage
	}
	value, err := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if err != nil || int64(len(value)) > maxBytes {
		return nil, errUnsafeStorage
	}
	return value, nil
}

func writeRootPrivateFileAtomic(path string, value []byte) error {
	directory := filepath.Dir(path)
	if err := validateRootPrivateDirectory(directory); err != nil {
		return err
	}
	if existing, err := os.Lstat(path); err == nil {
		if !existing.Mode().IsRegular() || existing.Mode().Perm() != 0600 || !rootOwnedSecretPath(existing, true) {
			return errUnsafeStorage
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	temp, err := os.CreateTemp(directory, ".levik-secure-*")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	committed := false
	defer func() {
		_ = temp.Close()
		if !committed {
			_ = os.Remove(tempPath)
		}
	}()
	if err := temp.Chmod(0600); err != nil {
		return err
	}
	if _, err := temp.Write(value); err != nil {
		return err
	}
	if err := temp.Sync(); err != nil {
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tempPath, path); err != nil {
		return err
	}
	dir, err := os.Open(directory)
	if err != nil {
		return err
	}
	defer dir.Close()
	if err := dir.Sync(); err != nil {
		return err
	}
	committed = true
	return nil
}
