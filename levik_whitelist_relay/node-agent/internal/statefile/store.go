// SPDX-License-Identifier: AGPL-3.0-only

package statefile

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
	"time"
)

const (
	maxStateBytes = 8 << 20
	stateVersion  = 2
)

var ErrCorrupt = errors.New("node-agent state is corrupt")

var (
	leaseRefPattern       = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
	idempotencyRefPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)
	labelPattern          = regexp.MustCompile(`^lr1-[a-z2-7]{32}$`)
)

type Record struct {
	LeaseRef           string `json:"lease_ref"`
	Label              string `json:"label"`
	Revision           uint64 `json:"revision"`
	CredentialRevision uint64 `json:"credential_revision"`
	IdempotencyRef     string `json:"idempotency_ref"`
	Operation          string `json:"operation"`
	ExpiresAt          int64  `json:"expires_at"`
	UpdatedAt          int64  `json:"updated_at"`
}

type diskState struct {
	Version int               `json:"version"`
	Records map[string]Record `json:"records"`
}

type Store struct {
	mu      sync.Mutex
	path    string
	records map[string]Record
	now     func() time.Time
}

func Open(path string) (*Store, error) {
	if !filepath.IsAbs(path) {
		return nil, errors.New("state path must be absolute")
	}
	if err := validateParent(path); err != nil {
		return nil, err
	}
	store := &Store{path: path, records: make(map[string]Record), now: time.Now}
	if err := store.load(); err != nil {
		return nil, err
	}
	return store, nil
}

func (s *Store) load() error {
	fd, err := syscall.Open(s.path, syscall.O_RDONLY|syscall.O_CLOEXEC|syscall.O_NOFOLLOW, 0)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	file := os.NewFile(uintptr(fd), s.path)
	if file == nil {
		_ = syscall.Close(fd)
		return ErrCorrupt
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return ErrCorrupt
	}
	stat, statOK := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Size() > maxStateBytes || !statOK || stat.Uid != 0 || stat.Nlink != 1 {
		return ErrCorrupt
	}
	var state diskState
	decoder := json.NewDecoder(io.LimitReader(file, maxStateBytes+1))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&state); err != nil || state.Version != stateVersion || state.Records == nil {
		return ErrCorrupt
	}
	for key, record := range state.Records {
		if !validRecord(key, record) {
			return ErrCorrupt
		}
	}
	s.records = state.Records
	return nil
}

func (s *Store) Get(leaseRef string) (Record, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.records[leaseRef]
	return record, ok
}

func (s *Store) Put(record Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validRecord(record.LeaseRef, record) {
		return ErrCorrupt
	}
	record.UpdatedAt = s.now().UTC().Unix()
	next := make(map[string]Record, len(s.records)+1)
	for key, value := range s.records {
		next[key] = value
	}
	next[record.LeaseRef] = record
	if err := persist(s.path, diskState{Version: stateVersion, Records: next}); err != nil {
		return err
	}
	s.records = next
	return nil
}

func persist(path string, state diskState) error {
	directory := filepath.Dir(path)
	if err := validateParent(path); err != nil {
		return err
	}
	temp, err := os.CreateTemp(directory, ".state-*")
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
	encoder := json.NewEncoder(temp)
	if err := encoder.Encode(state); err != nil {
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
		return fmt.Errorf("state directory sync: %w", err)
	}
	defer dir.Close()
	if err := dir.Sync(); err != nil {
		return fmt.Errorf("state directory sync: %w", err)
	}
	committed = true
	return nil
}

func validRecord(key string, record Record) bool {
	if key != record.LeaseRef || !leaseRefPattern.MatchString(record.LeaseRef) || !labelPattern.MatchString(record.Label) ||
		record.Revision == 0 || record.CredentialRevision == 0 || record.CredentialRevision > record.Revision ||
		!idempotencyRefPattern.MatchString(record.IdempotencyRef) || record.ExpiresAt < 0 {
		return false
	}
	switch record.Operation {
	case "create", "renew", "rotate", "revoke":
		return true
	default:
		return false
	}
}

func validateParent(path string) error {
	directory := filepath.Clean(filepath.Dir(path))
	canonical, err := filepath.EvalSymlinks(directory)
	if err != nil || canonical != directory {
		return ErrCorrupt
	}
	info, err := os.Lstat(directory)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 {
		return ErrCorrupt
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid != 0 {
		return ErrCorrupt
	}
	return nil
}
