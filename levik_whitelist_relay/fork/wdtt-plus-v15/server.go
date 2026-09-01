package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"crypto/cipher"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/tun"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"
)

const (
	wdttServerVersion     = "15"
	wgIfaceName           = "wdtt0"
	wgServerAddr          = "10.66.66.1"
	wgServerCIDR          = wgServerAddr + "/24"
	wgNetworkCIDR         = "10.66.66.0/24"
	wgPoolFirstHost       = 2
	wgPoolLastHost        = 250
	maxNodeDeviceCapacity = wgPoolLastHost - wgPoolFirstHost + 1
	defaultInternalWGPort = 56001
	defaultDNS            = "1.1.1.1"
	wgMTU                 = 1280
	keepalive             = 25
	dtlsKeepaliveByte     = 0xFF
	dtlsClientIdleTimeout = 90 * time.Second
)

func deviceLogRef(deviceID string) string {
	clean := strings.TrimSpace(deviceID)
	if clean == "" {
		return "unknown"
	}
	sum := sha256.Sum256([]byte(clean))
	return hex.EncodeToString(sum[:6])
}

// ==================== База данных ====================

type ClientDevice struct {
	DeviceID       string `json:"device_id"`
	IP             string `json:"ip"`
	PrivKey        string `json:"priv_key"`
	PubKey         string `json:"pub_key"`
	Name           string `json:"name,omitempty"`
	Manufacturer   string `json:"manufacturer,omitempty"`
	Brand          string `json:"brand,omitempty"`
	Model          string `json:"model,omitempty"`
	AndroidVersion string `json:"android_version,omitempty"`
	SDK            int    `json:"sdk,omitempty"`
	ABI            string `json:"abi,omitempty"`
	AppVersion     string `json:"app_version,omitempty"`
	Locale         string `json:"locale,omitempty"`
	Country        string `json:"country,omitempty"`
	TimeZone       string `json:"time_zone,omitempty"`
	RemoteIP       string `json:"remote_ip,omitempty"`
	LastSeenAt     int64  `json:"last_seen_at,omitempty"`
}

type PasswordEntry struct {
	DeviceID       string                   `json:"device_id"`  // пусто = ещё не привязан
	ExpiresAt      int64                    `json:"expires_at"` // unix timestamp
	PurgeAfter     int64                    `json:"purge_after,omitempty"`
	DownBytes      int64                    `json:"down_bytes"` // скачано клиентом
	UpBytes        int64                    `json:"up_bytes"`   // отдано клиентом
	Traffic        []TrafficBucket          `json:"traffic,omitempty"`
	TrafficImports map[string]TrafficImport `json:"traffic_imports,omitempty"`
	Label          string                   `json:"label,omitempty"`
	VkHash         string                   `json:"vk_hash,omitempty"`
	Ports          string                   `json:"ports,omitempty"` // "dtls,wg,tun"
	IsDeactivated  bool                     `json:"is_deactivated,omitempty"`
	BindHistory    []BindHistoryEntry       `json:"bind_history,omitempty"`
}

type TrafficImport struct {
	DownBytes int64 `json:"down_bytes"`
	UpBytes   int64 `json:"up_bytes"`
	AppliedAt int64 `json:"applied_at"`
}

type AdminProfileEntry struct {
	VkHashes        string   `json:"vk_hashes,omitempty"`
	SecondaryVkHash string   `json:"secondary_vk_hash,omitempty"`
	ProfileName     string   `json:"profile_name,omitempty"`
	WorkersPerHash  int      `json:"workers_per_hash,omitempty"`
	Protocol        string   `json:"protocol,omitempty"`
	ListenPort      int      `json:"listen_port,omitempty"`
	SNI             string   `json:"sni,omitempty"`
	NoDNS           bool     `json:"no_dns,omitempty"`
	VpnDNSSelection string   `json:"vpn_dns_selection,omitempty"`
	VpnDNSCustom    string   `json:"vpn_dns_custom,omitempty"`
	Ports           string   `json:"ports,omitempty"`      // "dtls,wg,tun"
	DeviceIDs       []string `json:"device_ids,omitempty"` // устройства, подключавшиеся по main_password
	UpdatedAt       int64    `json:"updated_at,omitempty"`
}

type TrafficBucket struct {
	Date      string `json:"date"`
	DownBytes int64  `json:"down_bytes"`
	UpBytes   int64  `json:"up_bytes"`
}

type BindHistoryEntry struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name,omitempty"`
	DeviceIP   string `json:"device_ip,omitempty"`
	RemoteIP   string `json:"remote_ip,omitempty"`
	Country    string `json:"country,omitempty"`
	BoundAt    int64  `json:"bound_at,omitempty"`
	UnboundAt  int64  `json:"unbound_at,omitempty"`
	EventAt    int64  `json:"event_at,omitempty"`
	Status     string `json:"status"`
	Note       string `json:"note,omitempty"`
}

type Database struct {
	MainPassword   string                    `json:"main_password"`
	AdminID        string                    `json:"admin_id"`
	BotToken       string                    `json:"bot_token"`
	DNS            string                    `json:"dns,omitempty"`
	MaxPasswords   int                       `json:"max_passwords,omitempty"`
	DefaultPorts   string                    `json:"default_ports,omitempty"`
	PublicIP       string                    `json:"public_ip,omitempty"`
	AdminProfile   AdminProfileEntry         `json:"admin_profile,omitempty"`
	AdminDownBytes int64                     `json:"admin_down_bytes,omitempty"`
	AdminUpBytes   int64                     `json:"admin_up_bytes,omitempty"`
	AdminTraffic   []TrafficBucket           `json:"admin_traffic,omitempty"`
	Passwords      map[string]*PasswordEntry `json:"passwords"`
	Devices        map[string]*ClientDevice  `json:"devices"`
}

type deviceInfoPayload struct {
	Name           string `json:"name"`
	Manufacturer   string `json:"manufacturer"`
	Brand          string `json:"brand"`
	Model          string `json:"model"`
	AndroidVersion string `json:"android_version"`
	SDK            int    `json:"sdk"`
	ABI            string `json:"abi"`
	AppVersion     string `json:"app_version"`
	Locale         string `json:"locale"`
	Country        string `json:"country"`
	TimeZone       string `json:"time_zone"`
}

var (
	db                   *Database
	dbMutex              sync.Mutex
	dbFile               string
	adminPasswordByLabel = make(map[string]string)
)

var dbTrafficDirty int32

var serverDNS atomic.Value
var serverDefaultPorts atomic.Value
var serverPublicIPOverride atomic.Value

func setServerDNS(value string) {
	if strings.TrimSpace(value) == "" {
		value = defaultDNS
	}
	serverDNS.Store(value)
}

func getServerDNS() string {
	value, _ := serverDNS.Load().(string)
	if strings.TrimSpace(value) == "" {
		return defaultDNS
	}
	return value
}

func setServerDefaultPorts(value string) {
	value = strings.TrimSpace(value)
	if value == "" {
		value = "56000,56001,9000"
	}
	serverDefaultPorts.Store(value)
}

func getServerDefaultPorts() string {
	value, _ := serverDefaultPorts.Load().(string)
	if strings.TrimSpace(value) == "" {
		return "56000,56001,9000"
	}
	return value
}

func setServerPublicIPOverride(value string) {
	serverPublicIPOverride.Store(strings.TrimSpace(value))
}

func getServerPublicIPOverride() string {
	value, _ := serverPublicIPOverride.Load().(string)
	return strings.TrimSpace(value)
}

var serverWrapKeys = newWrapKeyStore()
var allowOwnerTransportAccess bool

const (
	passChars                    = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
	generatedPasswordLen         = 16
	defaultMaxGeneratedPasswords = 50
)

var maxGeneratedPasswords = defaultMaxGeneratedPasswords

func validateMaxGeneratedPasswords(value int) error {
	if value < 1 || value > maxNodeDeviceCapacity {
		return fmt.Errorf("max passwords must be in 1..%d (WireGuard address-pool capacity)", maxNodeDeviceCapacity)
	}
	return nil
}

func generatePassword() (string, error) {
	result := make([]byte, 0, generatedPasswordLen)
	// Discard the top remainder instead of reducing every random byte modulo
	// the alphabet size. This avoids modulo bias while retaining crypto/rand
	// as the only entropy source.
	limit := 256 - (256 % len(passChars))
	buffer := make([]byte, generatedPasswordLen)
	for len(result) < generatedPasswordLen {
		if _, err := rand.Read(buffer); err != nil {
			return "", fmt.Errorf("secure password generation: %w", err)
		}
		for _, raw := range buffer {
			if int(raw) >= limit {
				continue
			}
			result = append(result, passChars[int(raw)%len(passChars)])
			if len(result) == generatedPasswordLen {
				break
			}
		}
	}
	return string(result), nil
}

func normalizeClientPassword(input string) (string, error) {
	password := strings.TrimSpace(input)
	if len(password) != generatedPasswordLen {
		return "", fmt.Errorf("пароль клиента должен содержать ровно %d символов", generatedPasswordLen)
	}
	for _, ch := range password {
		if !strings.ContainsRune(passChars, ch) {
			return "", errors.New("пароль клиента содержит недопустимые символы")
		}
	}
	return password, nil
}

var publicIP string = ""

func getPublicIP() string {
	if override := getServerPublicIPOverride(); override != "" {
		return override
	}
	if publicIP != "" {
		return publicIP
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get("https://api.ipify.org")
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	defer resp.Body.Close()
	ipBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	publicIP = string(bytes.TrimSpace(ipBytes))
	return publicIP
}

func stripVkUrl(url string) string {
	url = strings.TrimSpace(url)
	if idx := strings.LastIndex(url, "/"); idx != -1 {
		url = url[idx+1:]
	}
	if idx := strings.Index(url, "?"); idx != -1 {
		url = url[:idx]
	}
	return strings.TrimSpace(url)
}

type wrapKeyEntry struct {
	identity accessIdentity
	key      []byte
	v2ID     [levikWrapIDLen]byte
}

type wrapKeyStore struct {
	mu          sync.RWMutex
	entries     []wrapKeyEntry
	byV2ID      map[[levikWrapIDLen]byte]wrapKeyEntry
	allowLegacy bool
}

func newWrapKeyStore() *wrapKeyStore {
	return &wrapKeyStore{byV2ID: make(map[[levikWrapIDLen]byte]wrapKeyEntry)}
}

func deriveWrapKey(password string) ([]byte, error) {
	if password == "" {
		return nil, errors.New("empty password")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(
		sha256.New,
		[]byte(password),
		[]byte("WDTT-WRAP-v1"),
		[]byte("rtp-obfs/chacha20poly1305"),
	)
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, fmt.Errorf("derive wrap key: %w", err)
	}
	return key, nil
}

func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}

func zeroBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

func (s *wrapKeyStore) SetPasswords(mainPassword string, generated []string) error {
	next := make([]wrapKeyEntry, 0, len(generated)+1)
	seen := make(map[string]struct{}, len(generated)+1)
	seenV2 := make(map[[levikWrapIDLen]byte]struct{}, len(generated)+1)

	if mainPassword != "" {
		key, err := deriveWrapKey(mainPassword)
		if err != nil {
			return err
		}
		v2ID := deriveLevikWrapID(mainPassword)
		next = append(next, wrapKeyEntry{
			identity: accessIdentity{id: "main", password: mainPassword, isMain: true},
			key:      key,
			v2ID:     v2ID,
		})
		seen["main"] = struct{}{}
		seenV2[v2ID] = struct{}{}
	}

	for _, password := range generated {
		if password == "" {
			continue
		}
		id := "pass:" + wrapKeyID(password)
		if _, exists := seen[id]; exists {
			continue
		}
		key, err := deriveWrapKey(password)
		if err != nil {
			for _, entry := range next {
				zeroBytes(entry.key)
			}
			return err
		}
		v2ID := deriveLevikWrapID(password)
		if _, exists := seenV2[v2ID]; exists {
			zeroBytes(key)
			for _, entry := range next {
				zeroBytes(entry.key)
			}
			return errors.New("WRAP v2 credential identifier collision")
		}
		next = append(next, wrapKeyEntry{
			identity: accessIdentity{id: id, password: password},
			key:      key,
			v2ID:     v2ID,
		})
		seen[id] = struct{}{}
		seenV2[v2ID] = struct{}{}
	}

	s.mu.Lock()
	old := s.entries
	s.entries = next
	s.byV2ID = make(map[[levikWrapIDLen]byte]wrapKeyEntry, len(next))
	for _, entry := range next {
		s.byV2ID[entry.v2ID] = entry
	}
	s.mu.Unlock()
	for _, entry := range old {
		aeadCache.Delete(string(entry.key))
		zeroBytes(entry.key)
	}
	return nil
}

func (s *wrapKeyStore) AddPassword(password string) error {
	key, err := deriveWrapKey(password)
	if err != nil {
		return err
	}
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for _, entry := range s.entries {
		if entry.identity.id == id {
			zeroBytes(key)
			return nil
		}
	}
	v2ID := deriveLevikWrapID(password)
	if _, exists := s.byV2ID[v2ID]; exists {
		zeroBytes(key)
		return errors.New("WRAP v2 credential identifier collision")
	}
	s.entries = append(s.entries, wrapKeyEntry{
		identity: accessIdentity{id: id, password: password},
		key:      key,
		v2ID:     v2ID,
	})
	s.byV2ID[v2ID] = s.entries[len(s.entries)-1]
	return nil
}

func (s *wrapKeyStore) RemovePassword(password string) {
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for i, entry := range s.entries {
		if entry.identity.id != id {
			continue
		}
		aeadCache.Delete(string(entry.key))
		delete(s.byV2ID, entry.v2ID)
		zeroBytes(entry.key)
		copy(s.entries[i:], s.entries[i+1:])
		s.entries[len(s.entries)-1] = wrapKeyEntry{}
		s.entries = s.entries[:len(s.entries)-1]
		return
	}
}

func (s *wrapKeyStore) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.entries)
}

func (s *wrapKeyStore) SetAllowLegacy(value bool) {
	s.mu.Lock()
	s.allowLegacy = value
	s.mu.Unlock()
}

func (s *wrapKeyStore) Unwrap(raw, dst []byte) ([]byte, accessIdentity, [levikWrapIDLen]byte, bool, int, error) {
	if !obfsIsRTPPacket(raw) {
		return nil, accessIdentity{}, [levikWrapIDLen]byte{}, false, 0, errors.New("wrap: non-obfs packet")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.entries) == 0 {
		return nil, accessIdentity{}, [levikWrapIDLen]byte{}, false, 0, errors.New("wrap: no active keys")
	}
	if credentialID, ok := obfsV2CredentialID(raw); ok {
		entry, exists := s.byV2ID[credentialID]
		if !exists {
			return nil, accessIdentity{}, [levikWrapIDLen]byte{}, true, 0, errors.New("wrap: unknown credential")
		}
		m, err := obfsUnwrapPacketV2(entry.key, credentialID, raw, dst)
		if err != nil {
			return nil, accessIdentity{}, [levikWrapIDLen]byte{}, true, 0, err
		}
		return append([]byte(nil), entry.key...), entry.identity, credentialID, true, m, nil
	}
	if !s.allowLegacy {
		return nil, accessIdentity{}, [levikWrapIDLen]byte{}, false, 0, errors.New("wrap: legacy disabled")
	}
	for _, entry := range s.entries {
		m, err := obfsUnwrapPacket(entry.key, raw, dst)
		if err == nil {
			return append([]byte(nil), entry.key...), entry.identity, [levikWrapIDLen]byte{}, false, m, nil
		}
	}
	return nil, accessIdentity{}, [levikWrapIDLen]byte{}, false, 0, errors.New("wrap: auth failed")
}

func refreshWrapKeysFromDBLocked() error {
	passwords := make([]string, 0, len(db.Passwords))
	for password, entry := range db.Passwords {
		if entry != nil && !entry.IsDeactivated && !isPasswordPurgeable(entry) {
			passwords = append(passwords, password)
		}
	}
	mainPassword := ""
	if allowOwnerTransportAccess {
		mainPassword = db.MainPassword
	}
	return serverWrapKeys.SetPasswords(mainPassword, passwords)
}

func rememberAdminDeviceID(profile *AdminProfileEntry, deviceID string) bool {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return false
	}
	for _, existing := range profile.DeviceIDs {
		if existing == deviceID {
			return false
		}
	}
	profile.DeviceIDs = append(profile.DeviceIDs, deviceID)
	return true
}

func adminDeviceIDSet(loaded *Database) map[string]struct{} {
	result := make(map[string]struct{}, len(loaded.AdminProfile.DeviceIDs))
	for _, deviceID := range loaded.AdminProfile.DeviceIDs {
		deviceID = strings.TrimSpace(deviceID)
		if deviceID != "" {
			result[deviceID] = struct{}{}
		}
	}
	return result
}

func initDB(dir, mainPass, adminID, botToken, dnsValue string) {
	if err := validateRootPrivateDirectory(filepath.Clean(dir)); err != nil {
		log.Fatalf("[DB] unsafe config directory: %v", err)
	}
	dbFile = filepath.Join(dir, "passwords.json")
	db = &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	data, err := readRootPrivateFile(dbFile, maxSecureDatabaseBytes)
	if err == nil {
		if err := json.Unmarshal(data, db); err != nil {
			log.Fatalf("[DB] corrupt passwords.json: %v", err)
		}
	} else if !os.IsNotExist(err) {
		log.Fatalf("[DB] unsafe passwords.json: %v", err)
	}
	if db.Passwords == nil {
		db.Passwords = make(map[string]*PasswordEntry)
	}
	if db.Devices == nil {
		db.Devices = make(map[string]*ClientDevice)
	}
	if mainPass != "" || db.MainPassword == "" {
		db.MainPassword = mainPass
	}
	if adminID != "" || db.AdminID == "" {
		db.AdminID = adminID
	}
	if botToken != "" || db.BotToken == "" {
		db.BotToken = botToken
	}
	if dnsValue == "" {
		dnsValue = db.DNS
	}
	if dnsValue == "" {
		dnsValue = defaultDNS
	}
	db.DNS = dnsValue
	setServerDNS(dnsValue)
	if db.MaxPasswords > 0 {
		if err := validateMaxGeneratedPasswords(db.MaxPasswords); err != nil {
			log.Fatalf("[DB] invalid persisted capacity: %v", err)
		}
		if maxGeneratedPasswords == defaultMaxGeneratedPasswords {
			maxGeneratedPasswords = db.MaxPasswords
		}
	}
	ownerCapacity := maxNodeDeviceCapacity
	if allowOwnerTransportAccess && strings.TrimSpace(db.MainPassword) != "" {
		ownerCapacity--
	}
	if maxGeneratedPasswords > ownerCapacity {
		log.Fatalf("[DB] max passwords %d exceeds usable device capacity %d", maxGeneratedPasswords, ownerCapacity)
	}
	db.MaxPasswords = maxGeneratedPasswords
	if strings.TrimSpace(db.DefaultPorts) == "" {
		db.DefaultPorts = "56000,56001,9000"
	}
	db.AdminProfile = normalizeAdminProfileForStorage(db.AdminProfile, db.DefaultPorts)
	setServerDefaultPorts(db.DefaultPorts)
	setServerPublicIPOverride(db.PublicIP)
	rebuildAdminLabelIndexLocked()
	saveDB()
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		log.Fatalf("[WRAP] init keys: %v", err)
	}
}

func rebuildAdminLabelIndexLocked() {
	next := make(map[string]string, len(db.Passwords))
	for password, entry := range db.Passwords {
		if entry == nil {
			continue
		}
		label := strings.TrimSpace(entry.Label)
		if label == "" {
			continue
		}
		if _, exists := next[label]; exists {
			// Empty is a fail-closed duplicate marker for imported legacy data.
			next[label] = ""
			continue
		}
		next[label] = password
	}
	adminPasswordByLabel = next
}

func saveDB() {
	rebuildAdminLabelIndexLocked()
	data, err := json.MarshalIndent(db, "", "  ")
	if err != nil {
		log.Printf("[DB] marshal error: %v", err)
		return
	}
	if err := writeRootPrivateFileAtomic(dbFile, data); err != nil {
		log.Fatalf("[DB] persistence failed closed: %v", err)
	}
}

func isPasswordExpired(entry *PasswordEntry) bool {
	return isPasswordExpiredAt(entry, time.Now().Unix())
}

func isPasswordExpiredAt(entry *PasswordEntry, nowUnix int64) bool {
	if entry == nil {
		return true
	}
	if entry.ExpiresAt == 0 {
		return false // бессрочный
	}
	return nowUnix >= entry.ExpiresAt
}

func passwordPurgeDeadline(entry *PasswordEntry) int64 {
	if entry == nil || entry.ExpiresAt == 0 {
		return 0
	}
	if entry.PurgeAfter > entry.ExpiresAt {
		return entry.PurgeAfter
	}
	return entry.ExpiresAt
}

func isPasswordRetainedExpiredAt(entry *PasswordEntry, nowUnix int64) bool {
	return isPasswordExpiredAt(entry, nowUnix) && !isPasswordPurgeableAt(entry, nowUnix)
}

func isPasswordPurgeable(entry *PasswordEntry) bool {
	return isPasswordPurgeableAt(entry, time.Now().Unix())
}

func isPasswordPurgeableAt(entry *PasswordEntry, nowUnix int64) bool {
	if entry == nil {
		return true
	}
	if entry.ExpiresAt < 0 {
		return true
	}
	deadline := passwordPurgeDeadline(entry)
	return deadline > 0 && nowUnix >= deadline
}

func setPasswordExpiryPreservingRetention(entry *PasswordEntry, expiresAt int64) {
	if entry == nil {
		return
	}
	retentionDuration := entry.PurgeAfter - entry.ExpiresAt
	entry.ExpiresAt = expiresAt
	entry.PurgeAfter = 0
	if expiresAt > 0 && retentionDuration > 0 {
		purgeAfter := expiresAt + retentionDuration
		if purgeAfter >= expiresAt {
			entry.PurgeAfter = purgeAfter
		}
	}
}

func getNextIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		used[dev.IP] = true
	}
	for i := wgPoolFirstHost; i <= wgPoolLastHost; i++ {
		ip := fmt.Sprintf("10.66.66.%d", i)
		if !used[ip] {
			return ip
		}
	}
	return ""
}

func removePeerFromWG(wgDev wgDevice, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
}

func upsertPeerInWG(wgDev wgDevice, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" || dev.IP == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, dev.IP))
}

func cleanupExpiredPasswordsLocked(wgDev wgDevice) int {
	return cleanupExpiredPasswordsLockedAt(wgDev, time.Now().Unix())
}

func cleanupExpiredPasswordsLockedAt(wgDev wgDevice, nowUnix int64) int {
	removed := 0
	adminDevices := adminDeviceIDSet(db)
	deviceCandidates := make(map[string]struct{})
	for p, entry := range db.Passwords {
		if isPasswordPurgeableAt(entry, nowUnix) {
			if entry != nil && entry.DeviceID != "" {
				markActiveBindUnbound(entry, entry.DeviceID, nowUnix)
				deviceCandidates[entry.DeviceID] = struct{}{}
			}
			delete(db.Passwords, p)
			serverWrapKeys.RemovePassword(p)
			removed++
		}
	}
	for deviceID := range deviceCandidates {
		if _, isAdminDevice := adminDevices[deviceID]; isAdminDevice || databaseUsesDeviceID(db, deviceID) {
			continue
		}
		removePeerFromWG(wgDev, db.Devices[deviceID])
		delete(db.Devices, deviceID)
	}
	return removed
}

func databaseUsesDeviceID(loaded *Database, deviceID string) bool {
	return databaseUsesDeviceIDExcept(loaded, deviceID, "")
}

func databaseUsesDeviceIDExcept(loaded *Database, deviceID, excludedPassword string) bool {
	if loaded == nil || deviceID == "" {
		return false
	}
	for password, entry := range loaded.Passwords {
		if excludedPassword != "" && password == excludedPassword {
			continue
		}
		if entry != nil && entry.DeviceID == deviceID {
			return true
		}
	}
	return false
}

func cleanupExpiredPasswords(wgDev wgDevice) int {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	removed := cleanupExpiredPasswordsLocked(wgDev)
	if removed > 0 {
		saveDB()
	}
	return removed
}

func cleanDeviceInfoText(value string, limit int) string {
	value = strings.TrimSpace(value)
	value = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, value)
	if limit > 0 && len(value) > limit {
		value = value[:limit]
	}
	return value
}

func parseDeviceInfoPayload(raw string) deviceInfoPayload {
	var info deviceInfoPayload
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 2048 {
		return info
	}
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		return deviceInfoPayload{}
	}
	info.Name = cleanDeviceInfoText(info.Name, 80)
	info.Manufacturer = cleanDeviceInfoText(info.Manufacturer, 40)
	info.Brand = cleanDeviceInfoText(info.Brand, 40)
	info.Model = cleanDeviceInfoText(info.Model, 80)
	info.AndroidVersion = cleanDeviceInfoText(info.AndroidVersion, 24)
	info.ABI = cleanDeviceInfoText(info.ABI, 32)
	info.AppVersion = cleanDeviceInfoText(info.AppVersion, 32)
	info.Locale = cleanDeviceInfoText(info.Locale, 32)
	info.Country = cleanDeviceInfoText(info.Country, 32)
	info.TimeZone = cleanDeviceInfoText(info.TimeZone, 64)
	if info.SDK < 0 || info.SDK > 1000 {
		info.SDK = 0
	}
	return info
}

func remoteIPFromAddr(addr net.Addr) string {
	if addr == nil {
		return ""
	}
	host, _, err := net.SplitHostPort(addr.String())
	if err == nil {
		return host
	}
	return addr.String()
}

func applyDeviceInfo(dev *ClientDevice, info deviceInfoPayload, remoteIP string, now int64) {
	if dev == nil {
		return
	}
	if info.Name != "" {
		dev.Name = info.Name
	}
	if info.Manufacturer != "" {
		dev.Manufacturer = info.Manufacturer
	}
	if info.Brand != "" {
		dev.Brand = info.Brand
	}
	if info.Model != "" {
		dev.Model = info.Model
	}
	if info.AndroidVersion != "" {
		dev.AndroidVersion = info.AndroidVersion
	}
	if info.SDK > 0 {
		dev.SDK = info.SDK
	}
	if info.ABI != "" {
		dev.ABI = info.ABI
	}
	if info.AppVersion != "" {
		dev.AppVersion = info.AppVersion
	}
	if info.Locale != "" {
		dev.Locale = info.Locale
	}
	if info.Country != "" {
		dev.Country = info.Country
	}
	if info.TimeZone != "" {
		dev.TimeZone = info.TimeZone
	}
	if remoteIP != "" {
		dev.RemoteIP = remoteIP
	}
	dev.LastSeenAt = now
}

func deviceDisplayNameFromInfo(deviceID string, info deviceInfoPayload) string {
	if info.Name != "" {
		return info.Name
	}
	parts := []string{}
	if info.Manufacturer != "" {
		parts = append(parts, info.Manufacturer)
	}
	if info.Model != "" {
		parts = append(parts, info.Model)
	}
	name := strings.TrimSpace(strings.Join(parts, " "))
	if name != "" {
		return name
	}
	if deviceID != "" {
		return deviceID
	}
	return "unknown"
}

func deviceDisplayName(dev *ClientDevice) string {
	if dev == nil {
		return ""
	}
	if strings.TrimSpace(dev.Name) != "" {
		return dev.Name
	}
	parts := []string{}
	if strings.TrimSpace(dev.Manufacturer) != "" {
		parts = append(parts, dev.Manufacturer)
	}
	if strings.TrimSpace(dev.Model) != "" {
		parts = append(parts, dev.Model)
	}
	name := strings.TrimSpace(strings.Join(parts, " "))
	if name != "" {
		return name
	}
	return dev.DeviceID
}

func appendBindHistory(entry *PasswordEntry, event BindHistoryEntry) {
	if entry == nil {
		return
	}
	if event.EventAt == 0 {
		event.EventAt = time.Now().Unix()
	}
	entry.BindHistory = append(entry.BindHistory, event)
	if len(entry.BindHistory) > 50 {
		entry.BindHistory = entry.BindHistory[len(entry.BindHistory)-50:]
	}
}

func markActiveBindUnbound(entry *PasswordEntry, deviceID string, ts int64) {
	if entry == nil || deviceID == "" {
		return
	}
	for i := len(entry.BindHistory) - 1; i >= 0; i-- {
		h := &entry.BindHistory[i]
		if h.DeviceID == deviceID && h.Status == "active" && h.UnboundAt == 0 {
			h.Status = "unbound"
			h.UnboundAt = ts
			h.EventAt = ts
			return
		}
	}
}

func expiredPasswordJanitor(ctx context.Context, wgDev wgDevice) {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
				log.Printf("[DB] Удалено истёкших паролей: %d", removed)
			}
		}
	}
}

func syncPersistedPeersToWG(_ wgDevice) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	count := 0
	for _, dev := range db.Devices {
		if dev.PubKey != "" && dev.IP != "" {
			count++
		}
	}
	if count > 0 {
		log.Printf("[WG] Сохранённых устройств: %d; peer'ы добавятся при новом GETCONF", count)
	}
}

// ==================== Пул буферов ====================

var bufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 1600)
		return &b
	},
}

func getBuf() *[]byte  { return bufPool.Get().(*[]byte) }
func putBuf(b *[]byte) { bufPool.Put(b) }

// ==================== Статистика ====================

var (
	totalBytesFromClient int64
	totalBytesToClient   int64
	activeConns          int32
	totalConns           int64
	natType              string = "Инициализация..."
	serverStartTime      time.Time
)

const trafficHistoryDays = 400

func trafficDayKey(t time.Time) string {
	return t.Format("2006-01-02")
}

func addTrafficBucket(buckets []TrafficBucket, day string, downBytes, upBytes int64) []TrafficBucket {
	if downBytes == 0 && upBytes == 0 {
		return buckets
	}
	for i := range buckets {
		if buckets[i].Date == day {
			buckets[i].DownBytes += downBytes
			buckets[i].UpBytes += upBytes
			return pruneTrafficBuckets(buckets, time.Now())
		}
	}
	buckets = append(buckets, TrafficBucket{
		Date:      day,
		DownBytes: downBytes,
		UpBytes:   upBytes,
	})
	return pruneTrafficBuckets(buckets, time.Now())
}

func pruneTrafficBuckets(buckets []TrafficBucket, now time.Time) []TrafficBucket {
	if len(buckets) == 0 {
		return buckets
	}
	cutoff := now.AddDate(0, 0, -trafficHistoryDays).Format("2006-01-02")
	write := 0
	for _, bucket := range buckets {
		if bucket.Date >= cutoff {
			buckets[write] = bucket
			write++
		}
	}
	return buckets[:write]
}

func addTrafficLocked(password string, isMainPassword bool, downBytes, upBytes int64) bool {
	if password == "" {
		return true
	}
	day := trafficDayKey(time.Now())
	if isMainPassword {
		db.AdminDownBytes += downBytes
		db.AdminUpBytes += upBytes
		db.AdminTraffic = addTrafficBucket(db.AdminTraffic, day, downBytes, upBytes)
		atomic.StoreInt32(&dbTrafficDirty, 1)
		return true
	}
	entry, ok := db.Passwords[password]
	if !ok || entry == nil || isPasswordExpired(entry) || entry.IsDeactivated {
		return false
	}
	entry.DownBytes += downBytes
	entry.UpBytes += upBytes
	entry.Traffic = addTrafficBucket(entry.Traffic, day, downBytes, upBytes)
	atomic.StoreInt32(&dbTrafficDirty, 1)
	return true
}

func statsLoop(ctx context.Context, configDir string) {
	serverStartTime = time.Now()
	statsFile := filepath.Join(configDir, "server.log")
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	defer func() {
		flushAccessTraffic()
		dbMutex.Lock()
		if atomic.SwapInt32(&dbTrafficDirty, 0) == 1 {
			saveDB()
		}
		dbMutex.Unlock()
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			flushAccessTraffic()
			fromC := atomic.LoadInt64(&totalBytesFromClient)
			toC := atomic.LoadInt64(&totalBytesToClient)
			active := atomic.LoadInt32(&activeConns)
			total := atomic.LoadInt64(&totalConns)
			uptime := time.Since(serverStartTime)

			log.Printf("[СТАТ] Активных: %d | Всего: %d | CPU: %.1f%% | RAM: %.1f%% | NAT: %s | ↑%.2f МБ | ↓%.2f МБ",
				active, total, runtimeCPUPercent(), runtimeMemoryPercent(), natType,
				float64(fromC)/1024/1024,
				float64(toC)/1024/1024,
			)

			// Пишем server.log
			dbMutex.Lock()
			numPasswords := len(db.Passwords)
			numDevices := len(db.Devices)
			if atomic.SwapInt32(&dbTrafficDirty, 0) == 1 {
				saveDB()
			}
			dbMutex.Unlock()

			uptimeStr := formatUptime(uptime)
			downGB := float64(toC) / (1024 * 1024 * 1024)
			upGB := float64(fromC) / (1024 * 1024 * 1024)

			statsJSON, _ := json.Marshal(map[string]interface{}{
				"active":    active,
				"total":     total,
				"nat":       natType,
				"uptime":    uptimeStr,
				"down_gb":   fmt.Sprintf("%.2f", downGB),
				"up_gb":     fmt.Sprintf("%.2f", upGB),
				"passwords": numPasswords,
				"devices":   numDevices,
				"timestamp": time.Now().Unix(),
			})
			os.WriteFile(statsFile, statsJSON, 0644)
		}
	}
}

func formatUptime(d time.Duration) string {
	days := int(d.Hours()) / 24
	hours := int(d.Hours()) % 24
	mins := int(d.Minutes()) % 60
	if days > 0 {
		return fmt.Sprintf("%dд %dч %dм", days, hours, mins)
	}
	if hours > 0 {
		return fmt.Sprintf("%dч %dм", hours, mins)
	}
	return fmt.Sprintf("%dм", mins)
}

// ==================== Утилиты ====================

func runCmd(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

func runCmdSilent(name string, args ...string) string {
	out, _ := exec.Command(name, args...).CombinedOutput()
	return strings.TrimSpace(string(out))
}

func commandExists(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

func isNetTimeout(err error) bool {
	ne, ok := err.(net.Error)
	return ok && ne.Timeout()
}

func getDefaultInterface() string {
	out := runCmdSilent("ip", "route", "show", "default")
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		for index := 0; index+1 < len(fields); index++ {
			if fields[index] == "dev" && fields[index+1] != "" {
				return fields[index+1]
			}
		}
	}
	out = runCmdSilent("ip", "-o", "link", "show")
	for _, line := range strings.Split(out, "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) < 2 {
			continue
		}
		name := strings.TrimSpace(strings.SplitN(parts[1], "@", 2)[0])
		if name != "" && name != "lo" && !strings.HasPrefix(name, "wg") && !strings.HasPrefix(name, "tun") && !strings.HasPrefix(name, "wdtt") {
			return name
		}
	}
	return ""
}

// ==================== Ключи ====================

type wgKeys struct {
	serverPrivate, serverPublic, clientPrivate, clientPublic string
}

func b64ToHex(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", err
	}
	if len(b) != 32 {
		return "", fmt.Errorf("key length %d != 32", len(b))
	}
	return hex.EncodeToString(b), nil
}

func generateKeyPair() (privB64, pubB64 string, err error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return "", "", err
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(priv[:]),
		base64.StdEncoding.EncodeToString(pub), nil
}

func loadOrGenerateKeys(dir string) (*wgKeys, error) {
	f := filepath.Join(dir, "wg-keys.dat")
	if data, err := readRootPrivateFile(f, 4096); err == nil {
		lines := strings.Split(strings.TrimSpace(string(data)), "\n")
		if len(lines) != 4 {
			return nil, errors.New("WireGuard key file has an invalid line count")
		}
		keys := &wgKeys{
			serverPrivate: strings.TrimSpace(lines[0]),
			serverPublic:  strings.TrimSpace(lines[1]),
			clientPrivate: strings.TrimSpace(lines[2]),
			clientPublic:  strings.TrimSpace(lines[3]),
		}
		for _, key := range []string{keys.serverPrivate, keys.serverPublic, keys.clientPrivate, keys.clientPublic} {
			if _, err := b64ToHex(key); err != nil {
				return nil, fmt.Errorf("WireGuard key file is invalid: %w", err)
			}
		}
		log.Printf("[WG] Ключи загружены из %s", f)
		return keys, nil
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("unsafe WireGuard key file: %w", err)
	}
	log.Println("[WG] Генерирую новые ключи...")
	sPriv, sPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	cPriv, cPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	keys := &wgKeys{sPriv, sPub, cPriv, cPub}
	if err := writeRootPrivateFileAtomic(f, []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n",
		keys.serverPrivate, keys.serverPublic,
		keys.clientPrivate, keys.clientPublic))); err != nil {
		return nil, fmt.Errorf("persist WireGuard keys: %w", err)
	}
	log.Printf("[WG] Ключи сохранены в %s", f)
	return keys, nil
}

// ==================== NAT ====================

func setupFullConeNAT(wgIface string) error {
	log.Println("[NAT] ══════════════════════════════════════")
	extIface := getDefaultInterface()
	if strings.TrimSpace(extIface) == "" {
		return errors.New("default network interface is unavailable")
	}
	log.Printf("[NAT] Внешний: %s", extIface)
	if !commandExists("iptables") {
		return errors.New("iptables is required for managed host networking")
	}
	policyOutput, err := exec.Command("iptables", "-S", "FORWARD").CombinedOutput()
	if err != nil || !iptablesForwardPolicyIsDrop(string(policyOutput)) {
		return errors.New("managed host networking requires an existing FORWARD policy DROP")
	}
	if err := os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644); err != nil {
		return fmt.Errorf("enable IPv4 forwarding: %w", err)
	}
	rules := [][]string{
		{"-t", "nat", "-C", "POSTROUTING", "-s", wgNetworkCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE"},
		{"-C", "FORWARD", "-i", wgIface, "-o", extIface, "-s", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "NEW,ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
		{"-C", "FORWARD", "-i", extIface, "-o", wgIface, "-d", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
	}
	insert := [][]string{
		{"-t", "nat", "-I", "POSTROUTING", "1", "-s", wgNetworkCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE"},
		{"-A", "FORWARD", "-i", wgIface, "-o", extIface, "-s", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "NEW,ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
		{"-A", "FORWARD", "-i", extIface, "-o", wgIface, "-d", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
	}
	for index, check := range rules {
		if err := exec.Command("iptables", check...).Run(); err == nil {
			continue
		}
		if output, err := exec.Command("iptables", insert[index]...).CombinedOutput(); err != nil {
			return fmt.Errorf("install stateful iptables rule: %s", strings.TrimSpace(string(output)))
		}
	}
	natType = "MASQUERADE stateful iptables ✅"
	log.Printf("[NAT] Режим: %s", natType)
	log.Println("[NAT] ══════════════════════════════════════")
	return nil
}

func verifyHostNetwork(wgIface string) error {
	forwarding, err := os.ReadFile("/proc/sys/net/ipv4/ip_forward")
	if err != nil || strings.TrimSpace(string(forwarding)) != "1" {
		return errors.New("declarative host network is not ready: IPv4 forwarding disabled")
	}
	extIface := getDefaultInterface()
	if strings.TrimSpace(extIface) == "" {
		return errors.New("declarative host network is not ready: default interface unavailable")
	}
	if verifyIPTablesHostNetwork(wgIface, extIface) == nil {
		natType = "declarative stateful iptables verified ✅"
		return nil
	}
	if verifyNftHostNetwork(wgIface, extIface) == nil {
		natType = "declarative stateful nftables verified ✅"
		return nil
	}
	return errors.New("declarative stateful NAT/forward rules are missing or unsafe")
}

func verifyIPTablesHostNetwork(wgIface, extIface string) error {
	if !commandExists("iptables") {
		return errors.New("iptables unavailable")
	}
	policyOutput, err := exec.Command("iptables", "-S", "FORWARD").CombinedOutput()
	if err != nil || !iptablesForwardPolicyIsDrop(string(policyOutput)) {
		return errors.New("iptables FORWARD policy is not DROP")
	}
	checks := [][]string{
		{"-t", "nat", "-C", "POSTROUTING", "-s", wgNetworkCIDR, "-o", extIface, "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "MASQUERADE"},
		{"-C", "FORWARD", "-i", wgIface, "-o", extIface, "-s", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "NEW,ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
		{"-C", "FORWARD", "-i", extIface, "-o", wgIface, "-d", wgNetworkCIDR, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-m", "comment", "--comment", "WDTT_MANAGED", "-j", "ACCEPT"},
	}
	for _, check := range checks {
		if err := exec.Command("iptables", check...).Run(); err != nil {
			return errors.New("iptables rule missing")
		}
	}
	return nil
}

func iptablesForwardPolicyIsDrop(output string) bool {
	for _, line := range strings.Split(output, "\n") {
		if strings.TrimSpace(line) == "-P FORWARD DROP" {
			return true
		}
	}
	return false
}

const (
	nftNATComment           = "levik-wlr-masquerade"
	nftForwardOutComment    = "levik-wlr-forward-out"
	nftForwardReturnComment = "levik-wlr-forward-return"
)

func verifyNftHostNetwork(wgIface, extIface string) error {
	if !commandExists("nft") {
		return errors.New("nft unavailable")
	}
	natOutput, err := exec.Command("nft", "list", "chain", "ip", "levik_nat", "postrouting").CombinedOutput()
	if err != nil {
		return errors.New("nft NAT chain unavailable")
	}
	forwardOutput, err := exec.Command("nft", "list", "chain", "inet", "levik_filter", "forward").CombinedOutput()
	if err != nil {
		return errors.New("nft forward chain unavailable")
	}
	return validateNftHostNetwork(string(natOutput), string(forwardOutput), wgIface, extIface)
}

func validateNftHostNetwork(natOutput, forwardOutput, wgIface, extIface string) error {
	if !strings.Contains(natOutput, "type nat hook postrouting") {
		return errors.New("nft postrouting chain is not a NAT base chain")
	}
	natRule, ok := nftRuleByComment(natOutput, nftNATComment)
	if !ok || !nftRuleContains(natRule,
		"ip saddr "+wgNetworkCIDR,
		"oifname \""+extIface+"\"",
		"masquerade",
	) {
		return errors.New("nft masquerade rule missing")
	}
	if nftCommentCount(natOutput, nftNATComment) != 1 || nftHasUnlabelledVerdict(natOutput, "masquerade", nftNATComment) || nftRuleHasWords(natOutput, "snat") {
		return errors.New("nft NAT chain contains unexpected address-translation rules")
	}
	if !strings.Contains(forwardOutput, "type filter hook forward") || !strings.Contains(forwardOutput, "policy drop") {
		return errors.New("nft forward chain must be a base chain with policy drop")
	}
	outRule, ok := nftRuleByComment(forwardOutput, nftForwardOutComment)
	if !ok || !nftRuleContains(outRule,
		"iifname \""+wgIface+"\"",
		"oifname \""+extIface+"\"",
		"ip saddr "+wgNetworkCIDR,
		"ct state",
		"accept",
	) || !nftRuleHasWords(outRule, "new", "established", "related") {
		return errors.New("nft outbound stateful rule missing")
	}
	returnRule, ok := nftRuleByComment(forwardOutput, nftForwardReturnComment)
	if !ok || !nftRuleContains(returnRule,
		"iifname \""+extIface+"\"",
		"oifname \""+wgIface+"\"",
		"ip daddr "+wgNetworkCIDR,
		"ct state",
		"accept",
	) || !nftRuleHasWords(returnRule, "established", "related") || nftRuleHasWords(returnRule, "new") {
		return errors.New("nft return stateful rule missing or permits new flows")
	}
	if nftCommentCount(forwardOutput, nftForwardOutComment) != 1 || nftCommentCount(forwardOutput, nftForwardReturnComment) != 1 ||
		nftHasUnexpectedForwardAccept(forwardOutput, nftForwardOutComment, nftForwardReturnComment) {
		return errors.New("nft forward chain contains unexpected accept rules")
	}
	return nil
}

func nftRuleByComment(output, comment string) (string, bool) {
	needle := "comment \"" + comment + "\""
	for _, line := range strings.Split(output, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.Contains(trimmed, needle) {
			return trimmed, true
		}
	}
	return "", false
}

func nftRuleContains(rule string, fragments ...string) bool {
	for _, fragment := range fragments {
		if !strings.Contains(rule, fragment) {
			return false
		}
	}
	return true
}

func nftCommentCount(output, comment string) int {
	return strings.Count(output, "comment \""+comment+"\"")
}

func nftHasUnlabelledVerdict(output, verdict string, allowedComments ...string) bool {
	for _, line := range strings.Split(output, "\n") {
		if !nftRuleHasWords(line, verdict) {
			continue
		}
		allowed := false
		for _, comment := range allowedComments {
			if strings.Contains(line, "comment \""+comment+"\"") {
				allowed = true
				break
			}
		}
		if !allowed {
			return true
		}
	}
	return false
}

func nftHasUnexpectedForwardAccept(output string, allowedComments ...string) bool {
	for _, line := range strings.Split(output, "\n") {
		if !nftRuleHasWords(line, "accept") {
			continue
		}
		allowed := false
		for _, comment := range allowedComments {
			if strings.Contains(line, "comment \""+comment+"\"") {
				allowed = true
				break
			}
		}
		if allowed || nftIsExactEstablishedReturnRule(line) {
			continue
		}
		return true
	}
	return false
}

func nftIsExactEstablishedReturnRule(rule string) bool {
	normalized := strings.NewReplacer("{", " ", "}", " ", ",", " ", ";", " ").Replace(strings.TrimSpace(rule))
	fields := strings.Fields(normalized)
	if len(fields) != 5 || fields[0] != "ct" || fields[1] != "state" || fields[4] != "accept" {
		return false
	}
	return (fields[2] == "established" && fields[3] == "related") ||
		(fields[2] == "related" && fields[3] == "established")
}

func nftRuleHasWords(rule string, words ...string) bool {
	normalized := strings.NewReplacer("{", " ", "}", " ", ",", " ", ";", " ").Replace(rule)
	fields := strings.Fields(normalized)
	available := make(map[string]struct{}, len(fields))
	for _, field := range fields {
		available[field] = struct{}{}
	}
	for _, word := range words {
		if _, ok := available[word]; !ok {
			return false
		}
	}
	return true
}

// ==================== WireGuard ====================

func startUserspaceWG(keys *wgKeys, wgPort int, manageHostNetwork bool) (*device.Device, error) {
	runCmdSilent("ip", "link", "del", wgIfaceName)
	time.Sleep(100 * time.Millisecond)

	tunDev, err := tun.CreateTUN(wgIfaceName, wgMTU)
	if err != nil {
		return nil, fmt.Errorf("CreateTUN: %w", err)
	}

	ifaceName, err := tunDev.Name()
	if err != nil {
		tunDev.Close()
		return nil, fmt.Errorf("TUN name: %w", err)
	}

	logger := device.NewLogger(device.LogLevelError, "[WG] ")
	bind := conn.NewDefaultBind()
	dev := device.NewDevice(tunDev, bind, logger)

	serverPrivHex, _ := b64ToHex(keys.serverPrivate)

	if err := dev.IpcSet(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\n",
		serverPrivHex, wgPort,
	)); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}

	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("device.Up: %w", err)
	}

	if err := configureInterface(ifaceName); err != nil {
		dev.Close()
		return nil, err
	}

	var networkErr error
	if manageHostNetwork {
		networkErr = setupFullConeNAT(ifaceName)
	} else {
		networkErr = verifyHostNetwork(ifaceName)
	}
	if networkErr != nil {
		dev.Close()
		return nil, networkErr
	}

	go func() {
		uapiFile, err := ipc.UAPIOpen(ifaceName)
		if err != nil {
			return
		}
		uapi, err := ipc.UAPIListen(ifaceName, uapiFile)
		if err != nil {
			return
		}
		defer uapi.Close()
		for {
			c, err := uapi.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()

	log.Printf("[WG] Запущен на порту %d", wgPort)
	return dev, nil
}

func configureInterface(ifaceName string) error {
	for _, cmd := range [][]string{
		{"ip", "addr", "add", wgServerCIDR, "dev", ifaceName},
		{"ip", "link", "set", "mtu", fmt.Sprintf("%d", wgMTU), "dev", ifaceName},
		{"ip", "link", "set", ifaceName, "up"},
	} {
		out, err := runCmd(cmd[0], cmd[1:]...)
		if err != nil && !strings.Contains(out, "File exists") {
			return fmt.Errorf("%s: %s", strings.Join(cmd, " "), out)
		}
	}
	return nil
}

func buildClientConfig(serverPublic, clientPrivate, clientIP, clientPort string) string {
	return fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = %s/32
DNS = %s
MTU = %d

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:%s
PersistentKeepalive = %d`,
		clientPrivate, clientIP, getServerDNS(), wgMTU,
		serverPublic, clientPort, keepalive,
	)
}

// ==================== Main ====================

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-version") {
		fmt.Println(wdttServerVersion)
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "admin" {
		os.Exit(runAdminCLI(os.Args[2:]))
	}

	listen := flag.String("listen", "0.0.0.0:56000", "DTLS адрес")
	wgPort := flag.Int("wg-port", defaultInternalWGPort, "WireGuard UDP порт")
	configDir := flag.String("config-dir", "/etc/wdtt", "директория конфигурации")
	adminSocket := flag.String("admin-socket", "", "explicit absolute local admin socket path")
	mainPass := flag.String("password", "", "пароль владельца")
	mainPassFile := flag.String("password-file", "", "absolute owner-only file with owner password")
	adminID := flag.String("admin", "", "Telegram Admin ID")
	botToken := flag.String("bot-token", "", "Telegram Bot Token")
	botTokenFile := flag.String("bot-token-file", "", "absolute owner-only file with Telegram Bot Token")
	dnsValue := flag.String("dns", defaultDNS, "DNS для WireGuard-клиентов, через запятую")
	maxPasswordsFlag := flag.Int("max-passwords", defaultMaxGeneratedPasswords, "максимум активных сгенерированных паролей")
	maxWorkersFlag := flag.Int("max-workers-per-access", defaultMaxWorkersPerAccess, "максимум одновременных DTLS-воркеров одного доступа; 0 отключает лимит")
	maxHandshakesFlag := flag.Int("max-handshakes", defaultMaxHandshakes, "максимум одновременных DTLS-рукопожатий")
	handshakeRateFlag := flag.Float64("handshake-rate", defaultHandshakeRate, "допустимые DTLS-рукопожатия в секунду")
	clientMbpsFlag := flag.Float64("max-client-mbps", defaultClientMbps, "общий лимит Мбит/с на один доступ; 0 отключает")
	wgBackendFlag := flag.String("wg-backend", "auto", "WireGuard backend: auto, kernel или userspace")
	allowLegacyWrap := flag.Bool("allow-legacy-wrap", false, "allow O(N) WDTT v15 WRAP packets without Levik credential ID")
	allowOwnerAccess := flag.Bool("allow-owner-access", false, "allow the admin password as a data-plane credential (unsafe compatibility mode)")
	manageHostNetwork := flag.Bool("manage-host-network", false, "mutate host forwarding/NAT rules; default only verifies declarative stateful rules")
	flag.Parse()
	mainPassValue, err := chooseFileSecret(*mainPass, *mainPassFile, "password", true)
	if err != nil {
		log.Fatalf("[SECRET] owner password: %v", err)
	}
	botTokenValue, err := chooseFileSecret(*botToken, *botTokenFile, "bot-token", false)
	if err != nil {
		log.Fatalf("[SECRET] bot token: %v", err)
	}

	if err := validateMaxGeneratedPasswords(*maxPasswordsFlag); err != nil {
		log.Fatalf("[DB] invalid -max-passwords=%d: %v", *maxPasswordsFlag, err)
	}
	maxGeneratedPasswords = *maxPasswordsFlag
	if *maxWorkersFlag < 0 || *maxWorkersFlag > 128 {
		log.Printf("[LIMIT] -max-workers-per-access=%d некорректен, использую %d", *maxWorkersFlag, defaultMaxWorkersPerAccess)
		*maxWorkersFlag = defaultMaxWorkersPerAccess
	}
	if *maxHandshakesFlag < 1 || *maxHandshakesFlag > 256 {
		log.Printf("[LIMIT] -max-handshakes=%d некорректен, использую %d", *maxHandshakesFlag, defaultMaxHandshakes)
		*maxHandshakesFlag = defaultMaxHandshakes
	}
	if *handshakeRateFlag < 1 || *handshakeRateFlag > 1000 {
		log.Printf("[LIMIT] -handshake-rate=%.1f некорректен, использую %.1f", *handshakeRateFlag, defaultHandshakeRate)
		*handshakeRateFlag = defaultHandshakeRate
	}
	if *clientMbpsFlag < 0 || *clientMbpsFlag > 1000 {
		log.Printf("[LIMIT] -max-client-mbps=%.1f некорректен, использую %.1f", *clientMbpsFlag, defaultClientMbps)
		*clientMbpsFlag = defaultClientMbps
	}
	configureAccessRuntime(*maxWorkersFlag, *clientMbpsFlag)
	configureHandshakeLimits(*maxHandshakesFlag, *handshakeRateFlag)
	serverWrapKeys.SetAllowLegacy(*allowLegacyWrap)
	allowOwnerTransportAccess = *allowOwnerAccess

	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Println("══════════════════════════════════════════")
	log.Println("   WDTT Server v2 (Multi-User)")
	log.Println("══════════════════════════════════════════")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sig
		cancel()
		time.Sleep(2 * time.Second)
		os.Exit(0)
	}()

	initDB(*configDir, mainPassValue, *adminID, botTokenValue, *dnsValue)

	keys, err := loadOrGenerateKeys(*configDir)
	if err != nil {
		log.Fatalf("[WG] Ключи: %v", err)
	}

	wgDev, err := startWGBackend(*wgBackendFlag, keys, *wgPort, *manageHostNetwork)
	if err != nil {
		log.Fatalf("[WG] Запуск: %v", err)
	}
	if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
		log.Printf("[DB] Удалено истёкших паролей при старте: %d", removed)
	}
	syncPersistedPeersToWG(wgDev)
	if err := startAdminSocketAt(ctx, *configDir, *adminSocket, wgDev); err != nil {
		log.Fatalf("[ADMIN] Локальное управление: %v", err)
	}
	defer func() {
		wgDev.Close()
		runCmdSilent("ip", "link", "del", wgIfaceName)
	}()

	go statsLoop(ctx, *configDir)
	go systemMetricsLoop(ctx)
	go expiredPasswordJanitor(ctx, wgDev)
	go botLoop(botTokenValue, *adminID, wgDev)

	addr, _ := net.ResolveUDPAddr("udp", *listen)
	cert, _ := selfsign.GenerateSelfSigned()
	wrapListener, err := listenWrapped(addr, serverWrapKeys)
	if err != nil {
		log.Fatalf("[WRAP] %v", err)
	}

	listener, err := dtls.NewListenerWithOptions(wrapListener, dtls.WithCertificates(cert), dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret), dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256), dtls.WithConnectionIDGenerator(dtls.RandomCIDGenerator(8)))
	if err != nil {
		log.Fatalf("[DTLS] %v", err)
	}
	context.AfterFunc(ctx, func() { listener.Close() })

	wgEndpoint := fmt.Sprintf("127.0.0.1:%d", *wgPort)

	log.Printf("   DTLS: %s | WG: %s | NAT: %s", *listen, wgEndpoint, natType)
	log.Printf("   WRAP: password HKDF + RTP AEAD | keys: %d", serverWrapKeys.Count())
	log.Println("[SERVER] Готов")

	var wg sync.WaitGroup
	for {
		dtlsConn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				wg.Wait()
				return
			default:
			}
			continue
		}
		wg.Add(1)
		go func(c net.Conn) {
			defer wg.Done()
			defer c.Close()
			handleConn(ctx, c, wgEndpoint, wgDev, keys)
		}(dtlsConn)
	}
}

// ==================== Обработка соединений ====================

func denyExpiredAccess(clientConn net.Conn, identity accessIdentity) {
	if clientConn == nil || !identity.valid() || identity.isMain {
		return
	}
	_ = clientConn.SetReadDeadline(time.Now().Add(15 * time.Second))
	buf := make([]byte, 4096)
	n, err := clientConn.Read(buf)
	_ = clientConn.SetReadDeadline(time.Time{})
	if err != nil {
		return
	}
	request := strings.TrimSpace(string(buf[:n]))
	if !strings.HasPrefix(request, "GETCONF:") {
		return
	}
	parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(request, "GETCONF:")), "|")
	if len(parts) < 3 || parts[2] != identity.password {
		_, _ = clientConn.Write([]byte("DENIED:wrong_password"))
		return
	}
	_, _ = clientConn.Write([]byte("DENIED:expired"))
}

func handleConn(ctx context.Context, clientConn net.Conn, wgEndpoint string, wgDev wgDevice, keys *wgKeys) {
	atomic.AddInt64(&totalConns, 1)

	var connDevice *ClientDevice

	dtlsConn, ok := clientConn.(*dtls.Conn)
	if !ok {
		return
	}

	hctx, hcancel := context.WithTimeout(ctx, 30*time.Second)
	releaseHandshake, acquired := acquireHandshake(hctx)
	if !acquired {
		hcancel()
		return
	}
	if err := dtlsConn.HandshakeContext(hctx); err != nil {
		releaseHandshake()
		failures := atomic.AddInt64(&handshakeFailures, 1)
		if failures <= 5 || failures%100 == 0 {
			log.Printf("[DTLS] Рукопожатие от %s не завершено: %v", clientConn.RemoteAddr(), err)
		}
		hcancel()
		return
	}
	releaseHandshake()
	hcancel()

	// The WRAP identity is selected while DTLS reads its first encrypted packet.
	// It therefore becomes available only after a successful handshake.
	identity, ok := wrappedIdentity(clientConn.RemoteAddr())
	if !ok {
		failures := atomic.AddInt64(&handshakeFailures, 1)
		if failures <= 5 || failures%100 == 0 {
			log.Printf("[WRAP] Не удалось связать соединение %s с доступом", clientConn.RemoteAddr())
		}
		return
	}
	switch currentAccessIdentityState(identity) {
	case accessIdentityExpired:
		denyExpiredAccess(clientConn, identity)
		return
	case accessIdentityActive:
		// Continue with the normal worker path.
	default:
		return
	}
	sendWorkerPolicy := func() {
		if maxWorkers := configuredAccessWorkerLimit(); maxWorkers > 0 {
			_ = clientConn.SetWriteDeadline(time.Now().Add(3 * time.Second))
			_, _ = clientConn.Write([]byte(fmt.Sprintf("POLICY:max_workers=%d", maxWorkers)))
			_ = clientConn.SetWriteDeadline(time.Time{})
		}
	}
	var runtimeLease *accessRuntime
	var workerLease *accessWorkerLease
	var releaseWorker func()
	workerAdmitted := false

	buf := make([]byte, 1600)
	// GETCONF is sent immediately after the client handshake. Give it a short
	// pre-admission window so a new authenticated transport generation can replace
	// stale leases even when the old generation has filled the worker quota. Regular
	// data workers still enter the normal quota before waiting for user traffic.
	clientConn.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
	n, err := clientConn.Read(buf)
	if err != nil {
		if networkError, timedOut := err.(net.Error); timedOut && networkError.Timeout() {
			runtimeLease, workerLease, releaseWorker, ok =
				acquireAccessWorkerSession(identity, clientConn)
			if !ok {
				sendWorkerPolicy()
				return
			}
			workerAdmitted = true
			defer releaseWorker()
			clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
			n, err = clientConn.Read(buf)
		}
	}
	if err != nil {
		return
	}
	clientConn.SetReadDeadline(time.Time{})

	firstPacket := buf[:n]
	firstStr := string(firstPacket)

	if strings.HasPrefix(firstStr, "GETCONF:") {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(firstStr, "GETCONF:")), "|")
		clientPort := "9000"
		deviceID := "unknown"
		password := ""
		deviceInfo := deviceInfoPayload{}
		transportSession := ""
		if len(parts) > 0 {
			clientPort = parts[0]
		}
		if len(parts) > 1 {
			deviceID = parts[1]
		}
		if len(parts) > 2 {
			password = parts[2]
		}
		if len(parts) > 3 {
			deviceInfo = parseDeviceInfoPayload(parts[3])
		}
		if len(parts) > 4 {
			transportSession = strings.TrimSpace(parts[4])
		}
		if password != identity.password {
			atomic.AddInt64(&handshakeFailures, 1)
			_, _ = clientConn.Write([]byte("DENIED:wrong_password"))
			return
		}
		remoteIP := remoteIPFromAddr(clientConn.RemoteAddr())
		nowUnix := time.Now().Unix()
		authorized := false
		configResponse := ""

		dbMutex.Lock()

		// Проверяем пароль
		isMainPass := password != "" && password == db.MainPassword
		entry, isGenPass := db.Passwords[password]
		valid := isMainPass || (isGenPass && !isPasswordExpired(entry))

		if valid && isGenPass && entry.IsDeactivated {
			clientConn.Write([]byte("DENIED:deactivated"))
			log.Printf(
				"[WG] Отказ: пароль %s деактивирован, устройство %s",
				maskPassword(password),
				deviceLogRef(deviceID),
			)
			dbMutex.Unlock()
		} else if valid && isGenPass && entry.DeviceID != "" && entry.DeviceID != deviceID {
			appendBindHistory(entry, BindHistoryEntry{
				DeviceID:   deviceID,
				DeviceName: deviceDisplayNameFromInfo(deviceID, deviceInfo),
				RemoteIP:   remoteIP,
				Country:    deviceInfo.Country,
				EventAt:    nowUnix,
				Status:     "denied_mismatch",
				Note:       "пароль уже привязан к другому устройству",
			})
			saveDB()
			// Пароль уже привязан к другому устройству
			clientConn.Write([]byte("DENIED:device_mismatch"))
			log.Printf(
				"[WG] Отказ: пароль %s уже привязан; сохранённое устройство %s, запрос %s",
				maskPassword(password),
				deviceLogRef(entry.DeviceID),
				deviceLogRef(deviceID),
			)
			dbMutex.Unlock()
		} else if valid {
			newlyBound := false

			// Привязываем пароль к устройству при первом использовании
			if isGenPass && entry.DeviceID == "" {
				entry.DeviceID = deviceID
				newlyBound = true
				log.Printf(
					"[WG] Пароль %s привязан к устройству %s",
					maskPassword(password),
					deviceLogRef(deviceID),
				)
			}
			if isMainPass {
				rememberAdminDeviceID(&db.AdminProfile, deviceID)
			}

			dev, exists := db.Devices[deviceID]
			if !exists {
				dev = &ClientDevice{DeviceID: deviceID, IP: getNextIP()}
				privB64, pubB64, keyErr := generateKeyPair()
				if keyErr == nil && dev.IP != "" {
					dev.PrivKey = privB64
					dev.PubKey = pubB64
					applyDeviceInfo(dev, deviceInfo, remoteIP, nowUnix)
					db.Devices[deviceID] = dev
					saveDB()
					log.Printf("[WG] Новое устройство %s", deviceLogRef(deviceID))
				} else {
					dev = nil
				}
			} else {
				applyDeviceInfo(dev, deviceInfo, remoteIP, nowUnix)
			}
			if dev != nil {
				if newlyBound {
					appendBindHistory(entry, BindHistoryEntry{
						DeviceID:   deviceID,
						DeviceName: deviceDisplayName(dev),
						DeviceIP:   dev.IP,
						RemoteIP:   dev.RemoteIP,
						Country:    dev.Country,
						BoundAt:    nowUnix,
						EventAt:    nowUnix,
						Status:     "active",
					})
				}
				saveDB()
			}
			if dev != nil {
				connDevice = dev
				authorized = true
				configResponse = buildClientConfig(keys.serverPublic, dev.PrivKey, dev.IP, clientPort)
			} else {
				configResponse = "NOCONF"
			}
			dbMutex.Unlock()
		} else {
			if isGenPass && isPasswordExpired(entry) {
				clientConn.Write([]byte("DENIED:expired"))
				log.Printf(
					"[WG] Отказ: пароль %s истёк, устройство %s",
					maskPassword(password),
					deviceLogRef(deviceID),
				)
			} else {
				clientConn.Write([]byte("DENIED:wrong_password"))
				log.Printf("[WG] Отказ (неверный пароль), устройство %s", deviceLogRef(deviceID))
			}
			dbMutex.Unlock()
		}
		if !authorized {
			if configResponse != "" {
				_, _ = clientConn.Write([]byte(configResponse))
			}
			return
		}

		if workerAdmitted {
			if transportSession != "" &&
				!activateAccessSession(workerLease, deviceID, transportSession) {
				return
			}
		} else if transportSession != "" {
			runtimeLease, workerLease, releaseWorker, ok = acquireAccessWorkerForSession(
				identity,
				clientConn,
				deviceID,
				transportSession,
			)
		} else {
			runtimeLease, workerLease, releaseWorker, ok =
				acquireAccessWorkerSession(identity, clientConn)
		}
		if !workerAdmitted && !ok {
			sendWorkerPolicy()
			return
		}
		if !workerAdmitted {
			workerAdmitted = true
			defer releaseWorker()
		}
		_, _ = clientConn.Write([]byte(configResponse))

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	} else if !workerAdmitted {
		runtimeLease, workerLease, releaseWorker, ok =
			acquireAccessWorkerSession(identity, clientConn)
		if !ok {
			sendWorkerPolicy()
			return
		}
		workerAdmitted = true
		defer releaseWorker()
	}

	switch currentAccessIdentityState(identity) {
	case accessIdentityExpired:
		_, _ = clientConn.Write([]byte("DENIED:expired"))
		return
	case accessIdentityActive:
		// Continue with the authenticated request.
	default:
		return
	}

	atomic.AddInt32(&activeConns, 1)
	defer atomic.AddInt32(&activeConns, -1)

	if firstStr == "READY" {
		if !accessIdentityIsActive(identity) {
			return
		}
		clientConn.Write([]byte("READY_OK"))
		clientConn.SetReadDeadline(time.Now().Add(10 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
	}

	// WG прокси
	wgConn, err := net.Dial("udp", wgEndpoint)
	if err != nil {
		return
	}
	defer wgConn.Close()

	if uc, ok := wgConn.(*net.UDPConn); ok {
		uc.SetReadBuffer(2 * 1024 * 1024)
		uc.SetWriteBuffer(2 * 1024 * 1024)
	}

	if !accessIdentityIsActive(identity) {
		return
	}
	if connDevice != nil {
		upsertPeerInWG(wgDev, connDevice)
	}

	if err := runtimeLease.upload.wait(ctx, len(firstPacket)); err != nil {
		return
	}
	if _, err := wgConn.Write(firstPacket); err != nil {
		return
	}
	atomic.AddInt64(&totalBytesFromClient, int64(len(firstPacket)))
	recordAccessTraffic(runtimeLease, 0, int64(len(firstPacket)))

	pctx, pcancel := context.WithCancel(ctx)
	defer pcancel()

	if _, limited := accessIdentityExpiryUnix(identity); limited {
		go func() {
			for {
				expiresAt, stillLimited := accessIdentityExpiryUnix(identity)
				if !stillLimited {
					return
				}
				delay := time.Until(time.Unix(expiresAt, 0))
				if delay < 0 {
					delay = 0
				}
				timer := time.NewTimer(delay)
				select {
				case <-pctx.Done():
					if !timer.Stop() {
						<-timer.C
					}
					return
				case <-timer.C:
				}
				if !accessIdentityIsActive(identity) {
					pcancel()
					return
				}
				// The access was renewed before the old deadline. Read the new
				// expiration value and arm the monitor again.
			}
		}()
	}

	context.AfterFunc(pctx, func() {
		clientConn.SetDeadline(time.Now())
		wgConn.SetDeadline(time.Now())
	})

	var proxyWg sync.WaitGroup
	proxyWg.Add(2)

	// Клиент → WG
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		lastAccessCheck := time.Now()
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			clientConn.SetReadDeadline(time.Now().Add(dtlsClientIdleTimeout))
			nn, err := clientConn.Read(*b)
			if err != nil {
				return
			}
			// Reply to DTLS keepalive packets so clients can detect silent UDP stalls.
			if nn == 1 && (*b)[0] == dtlsKeepaliveByte {
				clientConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				_, err := clientConn.Write([]byte{dtlsKeepaliveByte})
				clientConn.SetWriteDeadline(time.Time{})
				if err != nil {
					return
				}
				continue
			}
			if time.Since(lastAccessCheck) >= 5*time.Second {
				if !accessIdentityIsActive(identity) {
					return
				}
				lastAccessCheck = time.Now()
			}
			if err := runtimeLease.upload.wait(pctx, nn); err != nil {
				return
			}
			if _, err := wgConn.Write((*b)[:nn]); err != nil {
				return
			}
			atomic.AddInt64(&totalBytesFromClient, int64(nn))
			recordAccessTraffic(runtimeLease, 0, int64(nn))
		}
	}()

	// WG → Клиент
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		lastAccessCheck := time.Now()
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			wgConn.SetReadDeadline(time.Now().Add(30 * time.Minute))
			nn, err := wgConn.Read(*b)
			if err != nil {
				if isNetTimeout(err) {
					if pctx.Err() != nil {
						return
					}
					continue
				}
				return
			}
			if time.Since(lastAccessCheck) >= 5*time.Second {
				if !accessIdentityIsActive(identity) {
					return
				}
				lastAccessCheck = time.Now()
			}
			if err := runtimeLease.download.wait(pctx, nn); err != nil {
				return
			}
			if _, err := clientConn.Write((*b)[:nn]); err != nil {
				return
			}
			atomic.AddInt64(&totalBytesToClient, int64(nn))
			recordAccessTraffic(runtimeLease, int64(nn), 0)
		}
	}()

	proxyWg.Wait()
}

const (
	wrapNonceLen = 12
	wrapKeyLen   = 32
)

var aeadCache sync.Map

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes", wrapKeyLen)
	}
	keyStr := string(key)
	if val, ok := aeadCache.Load(keyStr); ok {
		return val.(cipher.AEAD), nil
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(keyStr, aead)
	return aead, nil
}

// ==================== RTP Обфускация ====================

type ObfsConfig struct {
	SSRC        uint32
	PayloadType uint8
	PaddingMax  int
}

type ObfsState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

func NewObfsConfig() *ObfsConfig {
	var buf [4]byte
	rand.Read(buf[:])
	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: 111,
		PaddingMax:  24,
	}
}

func NewObfsState() *ObfsState {
	var buf [6]byte
	rand.Read(buf[:])
	return &ObfsState{
		initSeq: binary.BigEndian.Uint16(buf[0:2]),
		initTs:  binary.BigEndian.Uint32(buf[2:6]),
		count:   0,
	}
}

func obfsBuildNonce(ssrc uint32, seq uint16, ts uint32) [wrapNonceLen]byte {
	var n [wrapNonceLen]byte
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

func obfsWrapPacket(key, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	return obfsWrapPacketInto(nil, key, payload, cfg, state)
}

func obfsWrapPacketInto(dst, key, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}
	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)

	nonce := obfsBuildNonce(cfg.SSRC, seq, ts)
	padRand := 0
	if cfg.PaddingMax > 0 {
		var rndBuf [1]byte
		rand.Read(rndBuf[:])
		padRand = int(rndBuf[0]) % cfg.PaddingMax
	}
	padTotal := padRand + 1
	outLen := 12 + len(payload) + chacha20poly1305.Overhead + padTotal
	if cap(dst) < outLen {
		dst = make([]byte, outLen)
	}
	out := dst[:outLen]

	out[0] = 0x80 | 0x20
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	aead, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	sealed := aead.Seal(out[12:12], nonce[:], payload, out[:12])
	padStart := 12 + len(sealed)
	if padRand > 0 {
		rand.Read(out[padStart : padStart+padRand])
	}
	out[outLen-1] = byte(padTotal)
	return out, nil
}

func obfsUnwrapPacket(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < 13 {
		return 0, errors.New("obfs: packet too short")
	}
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-12 {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}
	ciphertextLen := payloadEnd - 12
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}
	nonce := obfsBuildNonce(ssrc, seq, ts)
	aead, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("obfs: cipher init: %w", err)
	}
	plain, err := aead.Open(dst[:0], nonce[:], wire[12:payloadEnd], wire[:12])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}
	return len(plain), nil
}

func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < 13 {
		return false
	}
	if (wire[0] >> 6) != 2 {
		return false
	}
	pt := wire[1] & 0x7F
	return pt == 111
}

func validateWrapKeyStore(keys *wrapKeyStore) error {
	if keys == nil {
		return errors.New("wrap: key store is nil")
	}
	return nil
}

func listenWrapped(addr *net.UDPAddr, keys *wrapKeyStore) (dtlsnet.PacketListener, error) {
	if err := validateWrapKeyStore(keys); err != nil {
		return nil, err
	}
	listenConfig := opportunisticUDPListenConfig{
		Backlog:         4096,
		AcceptFilter:    obfsIsRTPPacket,
		ReadBufferSize:  16 * 1024 * 1024,
		WriteBufferSize: 16 * 1024 * 1024,
		ReadBatchSize:   opportunisticUDPDefaultReadBatchSize,
		WriteBatchSize:  opportunisticUDPDefaultWriteBatchSize,
		WriteQueueSize:  opportunisticUDPDefaultWriteQueueSize,
	}
	inner, err := listenOpportunisticUDP("udp", addr, listenConfig)
	if err != nil {
		return nil, fmt.Errorf("wrap: udp listen: %w", err)
	}
	log.Printf(
		"[UDP] ReadBatch=%d | WriteBatch=opportunistic/%d | queue=%d",
		listenConfig.ReadBatchSize,
		listenConfig.WriteBatchSize,
		listenConfig.WriteQueueSize,
	)
	return &wrapPacketListener{
		inner: inner,
		keys:  keys,
	}, nil
}

type wrapPacketListener struct {
	inner dtlsnet.PacketListener
	keys  *wrapKeyStore
}

func (l *wrapPacketListener) Accept() (net.PacketConn, net.Addr, error) {
	pc, addr, err := l.inner.Accept()
	if err != nil {
		return pc, addr, err
	}
	return &wrapPacketConn{inner: pc, keys: l.keys}, addr, nil
}

func (l *wrapPacketListener) Close() error   { return l.inner.Close() }
func (l *wrapPacketListener) Addr() net.Addr { return l.inner.Addr() }

type wrapPacketConn struct {
	inner      net.PacketConn
	keys       *wrapKeyStore
	key        []byte
	identity   accessIdentity
	sessionKey string
	session    *wrappedSession
	selected   int32
	authLog    int32
	obfsCfg    *ObfsConfig
	obfsWrite  *ObfsState
	wrapV2     bool
	wrapV2ID   [levikWrapIDLen]byte
	replay     levikReplayWindow

	stateMu    sync.Mutex
	stateCond  *sync.Cond
	activeOps  int
	closing    bool
	closeOnce  sync.Once
	closeError error
}

var wrapWireBufferPool = sync.Pool{
	New: func() interface{} {
		buffer := make([]byte, 2048)
		return &buffer
	},
}

func (c *wrapPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	// Extra space for RTP header (12) + AEAD tag (16) + padding.
	buffer := wrapWireBufferPool.Get().(*[]byte)
	if cap(*buffer) < len(p)+80 {
		*buffer = make([]byte, len(p)+80)
	}
	buf := (*buffer)[:len(p)+80]
	defer wrapWireBufferPool.Put(buffer)
	n, addr, err := c.inner.ReadFrom(buf)
	if err != nil {
		return 0, addr, err
	}
	raw := buf[:n]

	c.stateMu.Lock()
	if c.closing {
		c.stateMu.Unlock()
		return 0, addr, net.ErrClosed
	}
	if atomic.LoadInt32(&c.selected) == 0 {
		key, identity, credentialID, isV2, m, uErr := c.keys.Unwrap(raw, p)
		if uErr != nil {
			c.stateMu.Unlock()
			if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
				log.Printf("[WRAP] Отказ: RTP AEAD auth failed from %s (keys=%d)", addr.String(), c.keys.Count())
			}
			return 0, addr, uErr
		}
		c.key = key
		c.identity = identity
		c.sessionKey, c.session = registerWrappedSession(addr, identity)
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
		c.wrapV2 = isV2
		c.wrapV2ID = credentialID
		if isV2 && !c.replay.Accept(raw) {
			c.stateMu.Unlock()
			return 0, addr, errors.New("wrap: replay rejected")
		}
		atomic.StoreInt32(&c.selected, 1)
		c.stateMu.Unlock()
		if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
			log.Printf("[WRAP] OK: ключ выбран для %s (keys=%d)", addr.String(), c.keys.Count())
		}
		return m, addr, nil
	}

	key := c.key
	c.activeOps++
	c.stateMu.Unlock()
	defer c.finishOperation()

	var m int
	var uErr error
	if c.wrapV2 {
		m, uErr = obfsUnwrapPacketV2(key, c.wrapV2ID, raw, p)
		if uErr == nil && !c.replay.Accept(raw) {
			uErr = errors.New("wrap: replay rejected")
		}
	} else {
		m, uErr = obfsUnwrapPacket(key, raw, p)
	}
	if uErr != nil {
		return 0, addr, fmt.Errorf("obfs unwrap: %w", uErr)
	}
	return m, addr, nil
}

func (c *wrapPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	c.stateMu.Lock()
	if c.closing {
		c.stateMu.Unlock()
		return 0, net.ErrClosed
	}
	if atomic.LoadInt32(&c.selected) == 0 || len(c.key) != wrapKeyLen {
		c.stateMu.Unlock()
		return 0, errors.New("wrap: key not selected")
	}
	if c.obfsCfg == nil || c.obfsWrite == nil {
		c.obfsCfg = NewObfsConfig()
		c.obfsWrite = NewObfsState()
	}
	key := c.key
	obfsCfg := c.obfsCfg
	obfsWrite := c.obfsWrite
	c.activeOps++
	c.stateMu.Unlock()
	defer c.finishOperation()

	buffer := wrapWireBufferPool.Get().(*[]byte)
	defer wrapWireBufferPool.Put(buffer)
	var wrapped []byte
	var wErr error
	if c.wrapV2 {
		wrapped, wErr = obfsWrapPacketV2(key, c.wrapV2ID, p, obfsCfg, obfsWrite)
	} else {
		wrapped, wErr = obfsWrapPacketInto((*buffer)[:0], key, p, obfsCfg, obfsWrite)
	}
	if wErr != nil {
		return 0, fmt.Errorf("obfs wrap: %w", wErr)
	}
	if _, err := c.inner.WriteTo(wrapped, addr); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wrapPacketConn) finishOperation() {
	c.stateMu.Lock()
	c.activeOps--
	if c.activeOps == 0 && c.stateCond != nil {
		c.stateCond.Broadcast()
	}
	c.stateMu.Unlock()
}

func (c *wrapPacketConn) Close() error {
	c.closeOnce.Do(func() {
		c.stateMu.Lock()
		c.closing = true
		if c.stateCond == nil {
			c.stateCond = sync.NewCond(&c.stateMu)
		}
		sessionKey := c.sessionKey
		session := c.session
		c.stateMu.Unlock()

		unregisterWrappedSession(sessionKey, session)
		closeError := c.inner.Close()

		c.stateMu.Lock()
		for c.activeOps > 0 {
			c.stateCond.Wait()
		}
		zeroBytes(c.key)
		c.key = nil
		c.closeError = closeError
		c.stateMu.Unlock()
	})

	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	return c.closeError
}
func (c *wrapPacketConn) LocalAddr() net.Addr                { return c.inner.LocalAddr() }
func (c *wrapPacketConn) SetDeadline(t time.Time) error      { return c.inner.SetDeadline(t) }
func (c *wrapPacketConn) SetReadDeadline(t time.Time) error  { return c.inner.SetReadDeadline(t) }
func (c *wrapPacketConn) SetWriteDeadline(t time.Time) error { return c.inner.SetWriteDeadline(t) }
