@file:Suppress("DataClassShouldBeImmutable")  // Well, yes, but actually no.

package org.quiltmc.community.database.entities

import dev.kord.common.entity.Snowflake
import kotlinx.serialization.Serializable
import org.quiltmc.community.database.Entity

@Serializable
@Suppress("ConstructorParameterNaming")  // MongoDB calls it that...
data class OwnedThread(
    override val _id: Snowflake,

    val owner: Snowflake,
    val guild: Snowflake,
) : Entity<Snowflake>
