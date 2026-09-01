package main

import (
	"strings"
	"testing"
)

const validNftNAT = `table ip levik_nat {
	chain postrouting {
		type nat hook postrouting priority srcnat; policy accept;
		ip saddr 10.66.66.0/24 oifname "eth0" counter masquerade comment "levik-wlr-masquerade"
	}
}`

const validNftForward = `table inet levik_filter {
	chain forward {
		type filter hook forward priority filter; policy drop;
		ct state established,related accept
		iifname "wdtt0" oifname "eth0" ip saddr 10.66.66.0/24 ct state { established, related, new } accept comment "levik-wlr-forward-out"
		iifname "eth0" oifname "wdtt0" ip daddr 10.66.66.0/24 ct state { established, related } accept comment "levik-wlr-forward-return"
	}
}`

func TestValidateNftHostNetworkAcceptsExactStatefulRules(t *testing.T) {
	if err := validateNftHostNetwork(validNftNAT, validNftForward, "wdtt0", "eth0"); err != nil {
		t.Fatalf("valid nft rules rejected: %v", err)
	}
}

func TestValidateNftHostNetworkAcceptsDeploymentSyntax(t *testing.T) {
	nat := `table ip levik_nat {
	chain postrouting {
		type nat hook postrouting priority srcnat; policy accept;
		oifname "ens3" ip saddr 10.66.66.0/24 masquerade comment "levik-wlr-masquerade"
	}
}`
	forward := `table inet levik_filter {
	chain forward {
		type filter hook forward priority filter; policy drop;
		ct state invalid drop
		ct state established,related accept
		iifname "wdtt0" oifname "ens3" ip saddr 10.66.66.0/24 ct state new,established,related accept comment "levik-wlr-forward-out"
		iifname "ens3" oifname "wdtt0" ip daddr 10.66.66.0/24 ct state established,related accept comment "levik-wlr-forward-return"
	}
}`
	if err := validateNftHostNetwork(nat, forward, "wdtt0", "ens3"); err != nil {
		t.Fatalf("deployment nft syntax rejected: %v", err)
	}
}

func TestValidateNftHostNetworkFailsClosed(t *testing.T) {
	tests := map[string]struct {
		nat     string
		forward string
	}{
		"forward policy accept": {
			nat:     validNftNAT,
			forward: strings.Replace(validNftForward, "policy drop", "policy accept", 1),
		},
		"missing exact NAT comment": {
			nat:     strings.Replace(validNftNAT, nftNATComment, "some-other-rule", 1),
			forward: validNftForward,
		},
		"wrong external interface": {
			nat:     validNftNAT,
			forward: strings.Replace(validNftForward, `oifname "eth0"`, `oifname "eth1"`, 1),
		},
		"outbound rule lacks new state": {
			nat:     validNftNAT,
			forward: strings.Replace(validNftForward, "established, related, new", "established, related", 1),
		},
		"return rule permits new flows": {
			nat:     validNftNAT,
			forward: strings.Replace(validNftForward, "established, related } accept comment \"levik-wlr-forward-return", "established, related, new } accept comment \"levik-wlr-forward-return", 1),
		},
		"wrong source network": {
			nat:     strings.Replace(validNftNAT, wgNetworkCIDR, "10.66.66.1/32", 1),
			forward: validNftForward,
		},
		"unexpected broad forward accept": {
			nat: validNftNAT,
			forward: strings.Replace(validNftForward, "\t}\n}",
				"\t\tip saddr 0.0.0.0/0 accept\n\t}\n}", 1),
		},
		"unexpected broad masquerade": {
			nat: strings.Replace(validNftNAT, "\t}\n}",
				"\t\tip saddr 0.0.0.0/0 masquerade\n\t}\n}", 1),
			forward: validNftForward,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			if err := validateNftHostNetwork(test.nat, test.forward, "wdtt0", "eth0"); err == nil {
				t.Fatal("unsafe nft rules accepted")
			}
		})
	}
}

func TestIPTablesForwardPolicyMustBeDrop(t *testing.T) {
	if !iptablesForwardPolicyIsDrop("-P INPUT DROP\n-P FORWARD DROP\n-P OUTPUT ACCEPT\n") {
		t.Fatal("DROP policy was not recognized")
	}
	if iptablesForwardPolicyIsDrop("-P FORWARD ACCEPT\n-A FORWARD -j DROP\n") {
		t.Fatal("fail-open FORWARD policy accepted")
	}
}

func TestValidateMaxGeneratedPasswordsMatchesAddressPool(t *testing.T) {
	if maxNodeDeviceCapacity != 249 {
		t.Fatalf("unexpected address-pool capacity: %d", maxNodeDeviceCapacity)
	}
	for _, valid := range []int{1, defaultMaxGeneratedPasswords, maxNodeDeviceCapacity} {
		if err := validateMaxGeneratedPasswords(valid); err != nil {
			t.Fatalf("valid capacity %d rejected: %v", valid, err)
		}
	}
	for _, invalid := range []int{0, -1, maxNodeDeviceCapacity + 1, 5000} {
		if err := validateMaxGeneratedPasswords(invalid); err == nil {
			t.Fatalf("invalid capacity %d accepted", invalid)
		}
	}
}
