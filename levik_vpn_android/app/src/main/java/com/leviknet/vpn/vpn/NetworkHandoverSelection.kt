package com.leviknet.vpn.vpn

/** A network change cannot expand the user's ordinary-service routing authority. */
internal fun networkHandoverCandidates(servers: List<TunnelServer>, policy: ServiceRouteProfile?): List<TunnelServer> =
    servers.filter { server ->
        server.isSmartAutoCandidate() && (policy == null ||
            ((policy.rules.isEmpty() || server.engine == TunnelEngineKind.XRAY) && policy.exitFor(server)?.state == "enabled"))
    }
