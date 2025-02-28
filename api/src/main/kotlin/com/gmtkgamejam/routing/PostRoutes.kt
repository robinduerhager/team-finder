package com.gmtkgamejam.routing

import com.auth0.jwt.JWT
import com.gmtkgamejam.enumFromStringSafe
import com.gmtkgamejam.models.*
import com.gmtkgamejam.respondJSON
import com.gmtkgamejam.services.AuthService
import com.gmtkgamejam.services.FavouritesService
import com.gmtkgamejam.services.PostService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bson.conversions.Bson
import org.litote.kmongo.*
import kotlin.math.min
import kotlin.reflect.full.memberProperties
import kotlin.text.Regex.Companion.escape

fun Application.configurePostRouting() {

    val authService = AuthService()
    val service = PostService()
    val favouritesService = FavouritesService()

    fun getFilterFromParameters(params: Parameters): List<Bson> {
        val filters = mutableListOf(PostItem::deletedAt eq null)

        params["description"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&description=`
            ?.map { it -> it.trim() }
            // The regex is the easiest way to check if a description contains a given substring
            ?.forEach { filters.add(PostItem::description regex escape(it).toRegex(RegexOption.IGNORE_CASE)) }

        val skillsPossessedSearchMode = params["skillsPossessedSearchMode"] ?: "and"
        params["skillsPossessed"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&skillsPossessed=`
            ?.mapNotNull { enumFromStringSafe<Skills>(it) }
            ?.map { PostItem::skillsPossessed contains it }
            ?.let { if (skillsPossessedSearchMode == "and") and(it) else or(it) }
            ?.let(filters::add)

        val skillsSoughtSearchMode = params["skillsSoughtSearchMode"] ?: "and"
        params["skillsSought"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&skillsSought=`
            ?.mapNotNull { enumFromStringSafe<Skills>(it) }
            ?.map { PostItem::skillsSought contains it }
            ?.let { if (skillsSoughtSearchMode == "and") and(it) else or(it) }
            ?.let(filters::add)

        params["tools"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&skillsSought=`
            ?.mapNotNull { enumFromStringSafe<Tools>(it) }
            ?.map { PostItem::preferredTools contains it }
            ?.let(filters::addAll)

        params["languages"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&languages=`
            ?.map { PostItem::languages contains it }
            ?.let { filters.add(or(it)) }

        params["availability"]?.split(',')
            ?.filter(String::isNotBlank) // Filter out empty `&availability=`
            ?.mapNotNull { enumFromStringSafe<Availability>(it) }
            ?.map { PostItem::availability eq it }
            // Availabilities are mutually exclusive, so treat it as inclusion search
            ?.let { filters.add(or(it)) }

        // If no timezones sent, lack of filters will search all timezones
        val timezoneRange = params["timezones"]?.split('/')
        if (timezoneRange != null && timezoneRange.size == 2) {
            val timezoneStart: Int = timezoneRange[0].toInt()
            val timezoneEnd: Int = timezoneRange[1].toInt()

            val timezones: MutableList<Int> = mutableListOf()
            if (timezoneStart < timezoneEnd) {
                // UTC-2 -> UTC+2 should be: [-2, -1, 0, 1, 2]
                timezones.addAll((timezoneStart..timezoneEnd))
            } else {
                // UTC+9 -> UTC-9 should be: [9, 10, 11, 12, -12, -11, -10, -9]
                timezones.addAll((timezoneStart..12))
                timezones.addAll((-12..timezoneEnd))
            }

            // Add all timezone searches as eq checks
            // It's brute force, but easier to confirm
            timezones
                .map { PostItem::timezoneOffsets contains it }
                .let { filters.add(or(it)) }
        }

        return filters
    }

    fun getSortFromParameters(params: Parameters): Bson {
        val sortByFieldName = params["sortBy"] ?: "createdAt"
        val sortByField = PostItem::class.memberProperties.first { prop -> prop.name == sortByFieldName }
        return when (params["sortDir"].toString()) {
            "asc" -> ascending(sortByField)
            "desc" -> descending(sortByField)
            else -> descending(sortByField)
        }
    }

    routing {
        route("/posts") {
            get {
                val params = call.parameters

                val posts = service.getPosts(and(getFilterFromParameters(params)), getSortFromParameters(params))

                // Set isFavourite on posts for this user if they're logged in
                call.request.header("Authorization")?.substring(7)
                    ?.let { JWT.decode(it) }?.getClaim("id")?.asString()
                    ?.let { authService.getTokenSet(it) }
                    ?.let { favouritesService.getFavouritesByUserId(it.discordId) }
                    ?.let { favouritesList ->
                        posts.map { it.isFavourite = favouritesList.postIds.contains(it.id) }
                    }

                call.respond(posts)
            }

            get("{id}") {
                val post: PostItem? = call.parameters["id"]?.let { service.getPost(it) }
                post?.let { return@get call.respond(it) }
                call.respondJSON("Post not found", status = HttpStatusCode.NotFound)
            }

            authenticate("auth-jwt") {

                post {
                    val data = call.receive<PostItemCreateDto>()

                    authService.getTokenSet(call)
                        ?.let {
                            data.authorId = it.discordId  // TODO: What about author name?
                            data.timezoneOffsets = data.timezoneOffsets.filter { tz -> tz >= -12 && tz <= 12 }.toSet()
                            if (service.getPostByAuthorId(it.discordId) != null) {
                                return@post call.respondJSON(
                                    "Cannot have duplicate posts",
                                    status = HttpStatusCode.BadRequest
                                )
                            }
                        }
                        ?.let { PostItem.fromCreateDto(data) }
                        ?.let { service.createPost(it) }
                        ?.let { return@post call.respond(it) }

                    call.respondJSON("Post could not be created", status = HttpStatusCode.NotFound)
                }

                get("favourites") {
                    val params = call.parameters

                    val favourites = authService.getTokenSet(call)
                        ?.let { favouritesService.getFavouritesByUserId(it.discordId) }

                    // Exit early if the user don't have any favourites set
                    if (favourites!!.postIds.isEmpty()) {
                        return@get call.respond(emptyList<PostItem>())
                    }

                    val favouritesFilters = mutableListOf<Bson>()
                    favourites.postIds.forEach {
                        favouritesFilters.add(and(PostItem::id eq it, PostItem::deletedAt eq null))
                    }

                    val posts = service.getPosts(
                        and(
                            or(favouritesFilters),
                            and(getFilterFromParameters(params))
                        ),
                        getSortFromParameters(params)
                    )
                    posts.map { post -> post.isFavourite = true }

                    call.respond(posts)
                }

                route("/mine") {
                    get {
                        authService.getTokenSet(call)
                            ?.let { service.getPostByAuthorId(it.discordId) }
                            ?.let { return@get call.respond(it) }

                        call.respondJSON("Post not found", status = HttpStatusCode.NotFound)
                    }

                    put {
                        val data = call.receive<PostItemUpdateDto>()

                        authService.getTokenSet(call)
                            ?.let { service.getPostByAuthorId(it.discordId) }
                            ?.let {
                                // FIXME: Don't just brute force update all given fields
                                it.author = data.author
                                    ?: it.author // We don't expect user to change, but track username updates
                                it.description = data.description ?: it.description
                                it.size = min(data.size ?: it.size, 20) // Limit team sizes to 20 people
                                it.skillsPossessed = data.skillsPossessed ?: it.skillsPossessed
                                it.skillsSought = data.skillsSought ?: it.skillsSought
                                it.preferredTools = data.preferredTools ?: it.preferredTools
                                it.availability = data.availability ?: it.availability
                                it.timezoneOffsets =
                                    (data.timezoneOffsets ?: it.timezoneOffsets).filter { tz -> tz >= -12 && tz <= 12 }
                                        .toSet()

                                service.updatePost(it)
                                return@put call.respond(it)
                            }

                        // TODO: Replace BadRequest with contextual response
                        call.respondJSON("Could not update Post", status = HttpStatusCode.BadRequest)
                    }

                    delete {
                        authService.getTokenSet(call)
                            ?.let { service.getPostByAuthorId(it.discordId) }
                            ?.let {
                                service.deletePost(it)
                                return@delete call.respondJSON("Post deleted", status = HttpStatusCode.OK)
                            }

                        // TODO: Replace BadRequest with contextual response
                        call.respondJSON("Could not delete Post", status = HttpStatusCode.BadRequest)
                    }
                }

                route("/report")
                {
                    post {
                        val data = call.receive<PostItemReportDto>()

                        service.getPost(data.id)?.let {
                            it.reportCount++
                            service.updatePost(it)
                            return@post call.respond(it)
                        }

                        call.respondJSON("Post not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }
}
