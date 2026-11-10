package com.swordfish.lemuroid.app.tv.home

import com.swordfish.lemuroid.lib.library.SystemID

/** RomM's icon names for each system (RomM serves them at /assets/platforms/<name>.svg or .ico). */
object PlatformIcons {
    fun slugs(system: SystemID): List<String> =
        when (system) {
            SystemID.NES -> listOf("nes", "famicom")
            SystemID.SNES -> listOf("snes", "sfam")
            SystemID.GENESIS -> listOf("genesis", "genesis-slash-megadrive", "megadrive")
            SystemID.SEGACD -> listOf("segacd")
            SystemID.GB -> listOf("gb")
            SystemID.GBC -> listOf("gbc")
            SystemID.GBA -> listOf("gba")
            SystemID.N64 -> listOf("n64")
            SystemID.SMS -> listOf("sms")
            SystemID.GG -> listOf("gamegear")
            SystemID.PSP -> listOf("psp")
            SystemID.NDS -> listOf("nds")
            SystemID.PSX -> listOf("psx", "ps")
            SystemID.ATARI2600 -> listOf("atari2600")
            SystemID.ATARI7800 -> listOf("atari7800")
            SystemID.LYNX -> listOf("lynx")
            SystemID.PC_ENGINE -> listOf("tg16", "pce")
            SystemID.NGP -> listOf("neo-geo-pocket", "ngp")
            SystemID.NGC -> listOf("neo-geo-pocket-color", "neo-geo-pocket")
            SystemID.WS -> listOf("wonderswan")
            SystemID.WSC -> listOf("wonderswan-color", "wonderswan")
            SystemID.FBNEO, SystemID.MAME2003PLUS -> listOf("arcade", "neogeoaes")
            else -> listOf(system.dbname)
        }
}
