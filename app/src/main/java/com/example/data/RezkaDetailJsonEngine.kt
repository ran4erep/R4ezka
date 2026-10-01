package com.example.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Высокопроизводительный движок сериализации и десериализации RezkaDetail
 * для оффлайн хранения. Работает без рефлексии и внешних зависимостей нативно через org.json.
 */
object RezkaDetailJsonEngine {

    fun toJson(detail: RezkaDetail): String {
        val root = JSONObject()
        root.put("id", detail.id)
        root.put("title", detail.title)
        root.put("originalTitle", detail.originalTitle)
        root.put("description", detail.description)
        root.put("imageUrl", detail.imageUrl)
        root.put("year", detail.year)
        root.put("releaseDate", detail.releaseDate)
        root.put("country", detail.country)
        root.put("countryFlag", detail.countryFlag)
        root.put("rating", detail.rating)
        root.put("director", detail.director)
        root.put("ageRestriction", detail.ageRestriction)
        root.put("duration", detail.duration)
        root.put("slogan", detail.slogan)
        root.put("seriesCollection", detail.seriesCollection)
        root.put("franchiseTitle", detail.franchiseTitle)
        root.put("trailerUrl", detail.trailerUrl)
        root.put("commentsTotalPages", detail.commentsTotalPages)
        root.put("commentsHasMore", detail.commentsHasMore)
        root.put("commentsTotalCount", detail.commentsTotalCount)
        root.put("type", detail.type.name)
        root.put("numericPostId", detail.numericPostId)
        root.put("isReleased", detail.isReleased)

        // RatingInfo
        val rInfo = JSONObject().apply {
            put("imdb", detail.ratingInfo.imdb)
            put("imdbVotes", detail.ratingInfo.imdbVotes)
            put("kinopoisk", detail.ratingInfo.kinopoisk)
            put("kinopoiskVotes", detail.ratingInfo.kinopoiskVotes)
            put("rezka", detail.ratingInfo.rezka)
            put("rezkaVotes", detail.ratingInfo.rezkaVotes)
        }
        root.put("ratingInfo", rInfo)

        // Genres
        val genresArr = JSONArray()
        detail.genres.forEach { genresArr.put(it) }
        root.put("genres", genresArr)

        // inCollections
        val collectionsArr = JSONArray()
        detail.inCollections.forEach { collectionsArr.put(it) }
        root.put("inCollections", collectionsArr)

        // collectionsList
        val collectionsListArr = JSONArray()
        detail.collectionsList.forEach { link ->
            collectionsListArr.put(JSONObject().apply {
                put("name", link.name)
                put("url", link.url)
            })
        }
        root.put("collectionsList", collectionsListArr)

        // seriesCollectionList
        val seriesColArr = JSONArray()
        detail.seriesCollectionList.forEach { link ->
            seriesColArr.put(JSONObject().apply {
                put("name", link.name)
                put("url", link.url)
            })
        }
        root.put("seriesCollectionList", seriesColArr)

        // franchiseItems
        val franchiseArr = JSONArray()
        detail.franchiseItems.forEach { fi ->
            franchiseArr.put(JSONObject().apply {
                put("id", fi.id)
                put("title", fi.title)
                put("url", fi.url)
                put("isCurrent", fi.isCurrent)
                put("year", fi.year)
            })
        }
        root.put("franchiseItems", franchiseArr)

        // directorsList
        val dirArr = JSONArray()
        detail.directorsList.forEach { link ->
            dirArr.put(JSONObject().apply {
                put("name", link.name)
                put("url", link.url)
            })
        }
        root.put("directorsList", dirArr)

        // actors
        val actorsArr = JSONArray()
        detail.actors.forEach { actorsArr.put(it) }
        root.put("actors", actorsArr)

        // actorsList
        val actorsListArr = JSONArray()
        detail.actorsList.forEach { link ->
            actorsListArr.put(JSONObject().apply {
                put("name", link.name)
                put("url", link.url)
            })
        }
        root.put("actorsList", actorsListArr)

        // comments
        val commArr = JSONArray()
        detail.comments.forEach { c ->
            commArr.put(JSONObject().apply {
                put("id", c.id)
                put("author", c.author)
                put("avatarUrl", c.avatarUrl)
                put("date", c.date)
                put("text", c.text)
                put("likes", c.likes)
                put("indent", c.indent)
            })
        }
        root.put("comments", commArr)

        // schedule
        val schedArr = JSONArray()
        detail.schedule.forEach { s ->
            schedArr.put(JSONObject().apply {
                put("seasonEpisode", s.seasonEpisode)
                put("title", s.title)
                put("releaseDate", s.releaseDate)
                put("ruReleaseDate", s.ruReleaseDate)
                put("isReleased", s.isReleased)
            })
        }
        root.put("schedule", schedArr)

        // translators
        val transArr = JSONArray()
        detail.translators.forEach { t ->
            transArr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("isDefault", t.isDefault)
                put("flagUrl", t.flagUrl)
                put("isPremium", t.isPremium)
                put("premiumUrl", t.premiumUrl)
                put("url", t.url)
            })
        }
        root.put("translators", transArr)

        // seasons
        val seasonsArr = JSONArray()
        detail.seasons.forEach { sea ->
            val seaObj = JSONObject()
            seaObj.put("id", sea.id)
            seaObj.put("name", sea.name)
            val epArr = JSONArray()
            sea.episodes.forEach { ep ->
                epArr.put(JSONObject().apply {
                    put("id", ep.id)
                    put("name", ep.name)
                    put("seasonId", ep.seasonId)
                    put("translatorId", ep.translatorId)
                })
            }
            seaObj.put("episodes", epArr)
            seasonsArr.put(seaObj)
        }
        root.put("seasons", seasonsArr)

        return root.toString()
    }

    fun fromJson(jsonStr: String): RezkaDetail? {
        if (jsonStr.isBlank()) return null
        return try {
            val root = JSONObject(jsonStr)

            val id = root.optString("id", "")
            val title = root.optString("title", "")
            val originalTitle = root.optString("originalTitle", "")
            val description = root.optString("description", "")
            val imageUrl = root.optString("imageUrl", "")
            val year = root.optString("year", "")
            val releaseDate = root.optString("releaseDate", "")
            val country = root.optString("country", "")
            val countryFlag = root.optString("countryFlag", "")
            val rating = root.optString("rating", "")
            val director = root.optString("director", "")
            val ageRestriction = root.optString("ageRestriction", "")
            val duration = root.optString("duration", "")
            val slogan = root.optString("slogan", "")
            val seriesCollection = root.optString("seriesCollection", "")
            val franchiseTitle = root.optString("franchiseTitle", "")
            val trailerUrl = root.optString("trailerUrl", "")
            val commentsTotalPages = root.optInt("commentsTotalPages", 1)
            val commentsHasMore = root.optBoolean("commentsHasMore", false)
            val commentsTotalCount = root.optInt("commentsTotalCount", 0)
            val numericPostId = root.optString("numericPostId", "")
            val isReleased = root.optBoolean("isReleased", true)

            val typeName = root.optString("type", "MOVIE")
            val rezkaType = try {
                RezkaType.valueOf(typeName)
            } catch (_: Exception) {
                RezkaType.MOVIE
            }

            // RatingInfo
            val rObj = root.optJSONObject("ratingInfo")
            val ratingInfo = if (rObj != null) {
                RatingInfo(
                    imdb = rObj.optString("imdb", ""),
                    imdbVotes = rObj.optString("imdbVotes", ""),
                    kinopoisk = rObj.optString("kinopoisk", ""),
                    kinopoiskVotes = rObj.optString("kinopoiskVotes", ""),
                    rezka = rObj.optString("rezka", ""),
                    rezkaVotes = rObj.optString("rezkaVotes", "")
                )
            } else RatingInfo()

            // Genres
            val genresList = ArrayList<String>()
            root.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.optString(i, "")
                    if (g.isNotEmpty()) genresList.add(g)
                }
            }

            // inCollections
            val inCollectionsList = ArrayList<String>()
            root.optJSONArray("inCollections")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optString(i, "")
                    if (c.isNotEmpty()) inCollectionsList.add(c)
                }
            }

            // collectionsList
            val collectionsList = ArrayList<LinkItem>()
            root.optJSONArray("collectionsList")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        collectionsList.add(LinkItem(obj.optString("name", ""), obj.optString("url", "")))
                    }
                }
            }

            // seriesCollectionList
            val seriesColList = ArrayList<LinkItem>()
            root.optJSONArray("seriesCollectionList")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        seriesColList.add(LinkItem(obj.optString("name", ""), obj.optString("url", "")))
                    }
                }
            }

            // franchiseItems
            val franchiseItems = ArrayList<FranchiseItem>()
            root.optJSONArray("franchiseItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        franchiseItems.add(
                            FranchiseItem(
                                id = obj.optString("id", ""),
                                title = obj.optString("title", ""),
                                url = obj.optString("url", ""),
                                isCurrent = obj.optBoolean("isCurrent", false),
                                year = obj.optString("year", "")
                            )
                        )
                    }
                }
            }

            // directorsList
            val directorsList = ArrayList<LinkItem>()
            root.optJSONArray("directorsList")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        directorsList.add(LinkItem(obj.optString("name", ""), obj.optString("url", "")))
                    }
                }
            }

            // actors
            val actors = ArrayList<String>()
            root.optJSONArray("actors")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optString(i, "")
                    if (a.isNotEmpty()) actors.add(a)
                }
            }

            // actorsList
            val actorsList = ArrayList<LinkItem>()
            root.optJSONArray("actorsList")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        actorsList.add(LinkItem(obj.optString("name", ""), obj.optString("url", "")))
                    }
                }
            }

            // comments
            val comments = ArrayList<CommentItem>()
            root.optJSONArray("comments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        comments.add(
                            CommentItem(
                                id = obj.optString("id", ""),
                                author = obj.optString("author", ""),
                                avatarUrl = obj.optString("avatarUrl", ""),
                                date = obj.optString("date", ""),
                                text = obj.optString("text", ""),
                                likes = obj.optString("likes", ""),
                                indent = obj.optInt("indent", 0)
                            )
                        )
                    }
                }
            }

            // schedule
            val schedule = ArrayList<ScheduleItem>()
            root.optJSONArray("schedule")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        schedule.add(
                            ScheduleItem(
                                seasonEpisode = obj.optString("seasonEpisode", ""),
                                title = obj.optString("title", ""),
                                releaseDate = obj.optString("releaseDate", ""),
                                ruReleaseDate = obj.optString("ruReleaseDate", ""),
                                isReleased = obj.optBoolean("isReleased", false)
                            )
                        )
                    }
                }
            }

            // translators
            val translators = ArrayList<Translator>()
            root.optJSONArray("translators")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        translators.add(
                            Translator(
                                id = obj.optString("id", ""),
                                name = obj.optString("name", ""),
                                isDefault = obj.optBoolean("isDefault", false),
                                flagUrl = obj.optString("flagUrl", ""),
                                isPremium = obj.optBoolean("isPremium", false),
                                premiumUrl = obj.optString("premiumUrl", ""),
                                url = obj.optString("url", "")
                            )
                        )
                    }
                }
            }

            // seasons
            val seasons = ArrayList<Season>()
            root.optJSONArray("seasons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { seaObj ->
                        val episodes = ArrayList<Episode>()
                        seaObj.optJSONArray("episodes")?.let { epArr ->
                            for (j in 0 until epArr.length()) {
                                epArr.optJSONObject(j)?.let { epObj ->
                                    episodes.add(
                                        Episode(
                                            id = epObj.optString("id", ""),
                                            name = epObj.optString("name", ""),
                                            seasonId = epObj.optInt("seasonId", seaObj.optInt("id", 1)),
                                            translatorId = epObj.optString("translatorId", "")
                                        )
                                    )
                                }
                            }
                        }
                        seasons.add(
                            Season(
                                id = seaObj.optInt("id", i + 1),
                                name = seaObj.optString("name", "Сезон ${i + 1}"),
                                episodes = episodes
                            )
                        )
                    }
                }
            }

            RezkaDetail(
                id = id,
                title = title,
                originalTitle = originalTitle,
                description = description,
                imageUrl = imageUrl,
                year = year,
                releaseDate = releaseDate,
                country = country,
                countryFlag = countryFlag,
                genres = genresList,
                rating = rating,
                ratingInfo = ratingInfo,
                director = director,
                directorsList = directorsList,
                ageRestriction = ageRestriction,
                duration = duration,
                slogan = slogan,
                inCollections = inCollectionsList,
                collectionsList = collectionsList,
                seriesCollection = seriesCollection,
                seriesCollectionList = seriesColList,
                franchiseTitle = franchiseTitle,
                franchiseItems = franchiseItems,
                actors = actors,
                actorsList = actorsList,
                trailerUrl = trailerUrl,
                comments = comments,
                commentsTotalPages = commentsTotalPages,
                commentsHasMore = commentsHasMore,
                commentsTotalCount = commentsTotalCount,
                schedule = schedule,
                type = rezkaType,
                translators = translators,
                seasons = seasons,
                numericPostId = numericPostId,
                isReleased = isReleased
            )
        } catch (_: Exception) {
            null
        }
    }
}
