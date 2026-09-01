// SPDX-License-Identifier: AGPL-3.0-only

package securefile

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const maxSecretBytes = 4096

var ErrUnsafeSecretFile = errors.New("unsafe secret file")

// Read reads a small secret through O_NOFOLLOW and rejects relative paths,
// non-regular files, empty values, and all group/world permission bits.
func Read(path string) ([]byte, error) {
	if !filepath.IsAbs(path) {
		return nil, fmt.Errorf("%w: path must be absolute", ErrUnsafeSecretFile)
	}
	parent := filepath.Clean(filepath.Dir(path))
	canonicalParent, err := filepath.EvalSymlinks(parent)
	if err != nil || canonicalParent != parent {
		return nil, fmt.Errorf("%w: parent path is not canonical", ErrUnsafeSecretFile)
	}
	parentInfo, err := os.Lstat(parent)
	if err != nil || !parentInfo.IsDir() || parentInfo.Mode().Perm()&0077 != 0 || !rootOwnedSingleLink(parentInfo, false) {
		return nil, fmt.Errorf("%w: unsafe parent directory", ErrUnsafeSecretFile)
	}
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, fmt.Errorf("%w: open failed", ErrUnsafeSecretFile)
	}
	file := os.NewFile(uintptr(fd), path)
	if file == nil {
		_ = syscall.Close(fd)
		return nil, ErrUnsafeSecretFile
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > maxSecretBytes || !rootOwnedSingleLink(info, true) {
		return nil, ErrUnsafeSecretFile
	}
	value, err := io.ReadAll(io.LimitReader(file, maxSecretBytes+1))
	if err != nil || len(value) > maxSecretBytes {
		return nil, ErrUnsafeSecretFile
	}
	value = []byte(strings.TrimSpace(string(value)))
	if len(value) == 0 {
		return nil, ErrUnsafeSecretFile
	}
	return value, nil
}

func rootOwnedSingleLink(info os.FileInfo, requireSingleLink bool) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != 0 {
		return false
	}
	return !requireSingleLink || stat.Nlink == 1
}
