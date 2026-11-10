package com.swordfish.lemuroid.lib.romm

import com.swordfish.lemuroid.lib.library.SystemID

/**
 * Which RomM platforms this app can play, and with which built-in system.
 * Any platform not listed here is left out of the library (for example 3DS and DOS, too slow on a TV).
 * Adding a system is one line: the RomM slug (or folder name) and the system it plays on.
 */
object RommPlatforms {
    private val bySlug: Map<String, SystemID> =
        mapOf(
            // Nintendo
            "nes" to SystemID.NES,
            "famicom" to SystemID.NES,
            "snes" to SystemID.SNES,
            "sfam" to SystemID.SNES,
            "gb" to SystemID.GB,
            "gbc" to SystemID.GBC,
            "gba" to SystemID.GBA,
            "n64" to SystemID.N64,
            "nds" to SystemID.NDS,
            // Sega
            "genesis" to SystemID.GENESIS,
            "megadrive" to SystemID.GENESIS,
            "genesis-slash-megadrive" to SystemID.GENESIS,
            "sms" to SystemID.SMS,
            "mastersystem" to SystemID.SMS,
            "gamegear" to SystemID.GG,
            "gg" to SystemID.GG,
            "segacd" to SystemID.SEGACD,
            "megacd" to SystemID.SEGACD,
            // Sony
            "psx" to SystemID.PSX,
            "ps" to SystemID.PSX,
            "playstation" to SystemID.PSX,
            "psp" to SystemID.PSP,
            // NEC
            "tg16" to SystemID.PC_ENGINE,
            "pcengine" to SystemID.PC_ENGINE,
            "pce" to SystemID.PC_ENGINE,
            "turbografx-cd" to SystemID.PC_ENGINE,
            "pcenginecd" to SystemID.PC_ENGINE,
            // Atari
            "atari2600" to SystemID.ATARI2600,
            "atari7800" to SystemID.ATARI7800,
            "lynx" to SystemID.LYNX,
            // SNK, Bandai
            "neo-geo-pocket" to SystemID.NGP,
            "ngp" to SystemID.NGP,
            "neo-geo-pocket-color" to SystemID.NGC,
            "ngpc" to SystemID.NGC,
            "wonderswan" to SystemID.WS,
            "ws" to SystemID.WS,
            "wonderswan-color" to SystemID.WSC,
            "wsc" to SystemID.WSC,
            // Arcade
            "arcade" to SystemID.FBNEO,
            "fbneo" to SystemID.FBNEO,
            "neogeoaes" to SystemID.FBNEO,
            "neogeomvs" to SystemID.FBNEO,
            "neogeo" to SystemID.FBNEO,
            "mame" to SystemID.MAME2003PLUS,
            "mame2003" to SystemID.MAME2003PLUS,
            "mame-2003-plus" to SystemID.MAME2003PLUS,
        )

    fun systemFor(
        slug: String?,
        fsSlug: String?,
    ): SystemID? {
        slug?.lowercase()?.let { s -> bySlug[s]?.let { return it } }
        fsSlug?.lowercase()?.let { s -> bySlug[s]?.let { return it } }
        return null
    }
}
