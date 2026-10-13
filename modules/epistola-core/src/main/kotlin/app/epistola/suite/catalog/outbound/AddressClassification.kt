// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Why an address is not an ordinary public one.
 *
 * Two classes rather than one, because an intranet deployment has a legitimate reason to fetch from
 * a private address and never one to fetch from a link-local one: that is where cloud metadata
 * endpoints live (`169.254.169.254`; AWS's IPv6 `fd00:ec2::254` is singled out for the same reason),
 * and `0.0.0.0` reaches the local host.
 */
enum class AddressClass {
    /** Refused even with `allow-private-networks`: link-local, unspecified, multicast, broadcast, reserved, documentation. */
    FORBIDDEN,

    /** Refused unless `allow-private-networks` or the host is in `allowed-hosts`: loopback, RFC 1918, CGNAT, ULA. */
    PRIVATE,
}

/**
 * Classifies an IP address for [OutboundUrlGuard]. Null means an ordinary public address.
 *
 * IPv6 forms that carry an IPv4 address inside them — IPv4-mapped, IPv4-compatible, 6to4 and NAT64 —
 * are classified by the address they carry, so `::ffff:127.0.0.1` is as private as `127.0.0.1`.
 */
object AddressClassification {

    private val AWS_METADATA_V6: ByteArray = InetAddress.ofLiteral("fd00:ec2::254").address

    fun classify(address: InetAddress): AddressClass? = when (address) {
        is Inet4Address -> classifyV4(address.address)
        is Inet6Address -> classifyV6(address.address)
        else -> AddressClass.FORBIDDEN
    }

    private fun classifyV4(b: ByteArray): AddressClass? {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        val o2 = b[2].toInt() and 0xff
        return when {
            o0 == 0 -> AddressClass.FORBIDDEN

            // 0.0.0.0/8 "this network" — 0.0.0.0 reaches the local host
            o0 == 169 && o1 == 254 -> AddressClass.FORBIDDEN

            // link-local, cloud metadata
            o0 >= 224 -> AddressClass.FORBIDDEN

            // multicast 224/4, reserved 240/4, broadcast
            o0 == 192 && o1 == 0 && o2 == 0 -> AddressClass.FORBIDDEN

            // IETF protocol assignments
            o0 == 192 && o1 == 0 && o2 == 2 -> AddressClass.FORBIDDEN

            // TEST-NET-1
            o0 == 198 && o1 == 51 && o2 == 100 -> AddressClass.FORBIDDEN

            // TEST-NET-2
            o0 == 203 && o1 == 0 && o2 == 113 -> AddressClass.FORBIDDEN

            // TEST-NET-3
            o0 == 127 -> AddressClass.PRIVATE

            // loopback
            o0 == 10 -> AddressClass.PRIVATE

            o0 == 172 && o1 in 16..31 -> AddressClass.PRIVATE

            o0 == 192 && o1 == 168 -> AddressClass.PRIVATE

            o0 == 100 && o1 in 64..127 -> AddressClass.PRIVATE

            // carrier-grade NAT
            o0 == 198 && o1 in 18..19 -> AddressClass.PRIVATE

            // benchmarking
            else -> null
        }
    }

    private fun classifyV6(b: ByteArray): AddressClass? {
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        val word0 = (first shl 8) or second
        val word1 = ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        val upperZero = (0 until 10).all { b[it].toInt() == 0 }

        return when {
            // ::/128 unspecified and ::1 loopback.
            (0 until 15).all { b[it].toInt() == 0 } && (b[15].toInt() and 0xff) <= 1 ->
                if (b[15].toInt() == 0) AddressClass.FORBIDDEN else AddressClass.PRIVATE

            // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (compatible, deprecated).
            upperZero && b[10].toInt() == -1 && b[11].toInt() == -1 -> classifyV4(b.copyOfRange(12, 16))

            upperZero && b[10].toInt() == 0 && b[11].toInt() == 0 -> classifyV4(b.copyOfRange(12, 16)) ?: AddressClass.FORBIDDEN

            // 64:ff9b::/96 well-known NAT64 prefix carries an IPv4 address in its last 32 bits.
            word0 == 0x0064 && word1 == 0xff9b && (4 until 12).all { b[it].toInt() == 0 } -> classifyV4(b.copyOfRange(12, 16))

            // 64:ff9b:1::/48 local-use NAT64.
            word0 == 0x0064 && word1 == 0xff9b -> AddressClass.PRIVATE

            // 2002::/16 6to4 carries an IPv4 address in bits 16..48.
            word0 == 0x2002 -> classifyV4(b.copyOfRange(2, 6))

            // 2001::/32 Teredo obfuscates the client address; refused outright rather than decoded.
            word0 == 0x2001 && word1 == 0x0000 -> AddressClass.FORBIDDEN

            // 2001:db8::/32 documentation.
            word0 == 0x2001 && word1 == 0x0db8 -> AddressClass.FORBIDDEN

            // 100::/64 discard-only.
            word0 == 0x0100 && (2 until 8).all { b[it].toInt() == 0 } -> AddressClass.FORBIDDEN

            b.contentEquals(AWS_METADATA_V6) -> AddressClass.FORBIDDEN

            // fd00:ec2::254, inside the ULA range below

            first == 0xff -> AddressClass.FORBIDDEN

            // multicast ff00::/8

            first == 0xfe && (second and 0xc0) == 0x80 -> AddressClass.FORBIDDEN

            // link-local fe80::/10

            first == 0xfe && (second and 0xc0) == 0xc0 -> AddressClass.PRIVATE

            // site-local fec0::/10, deprecated

            (first and 0xfe) == 0xfc -> AddressClass.PRIVATE

            // unique-local fc00::/7

            else -> null
        }
    }
}
