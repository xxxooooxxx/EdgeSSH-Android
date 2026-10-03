package com.lyrnox.edgessh.data

import java.util.UUID

/** 一条常用命令片段，可一键填入终端。 */
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
)
