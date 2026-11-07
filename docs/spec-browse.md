# Browse spec (from 30TV 3.2.3 decompile)
Envelope: {success: bool, result: {...}}. POST {endpoint}/action/{name}, form-urlencoded (arrays as repeated keys, booleans true/false).
- home_stream -> result.{hero_header,suggested,movies,series,anime}.posts[] (Title). (home also: new_releases, top_10)
- menu -> result.categories[], result.genres[] : {id,name,slug,count,image{webp{full,thumb},jpg{..}}}; skip slug 'coming-soon'.
- search(query, streamonly=true, page) -> result.posts/page/pages. min 3 chars.
- archive(slugs=<slug>, streamonly=true, page, orderby relevant|update|year|favorite|imdb-rate|30nama-rate, order DESC) -> posts/page/pages
- continue_watching -> result.posts[] (Title + data{season,episode,display_time}); dismiss(id)
- user_list list/add; user_mylist -> result.{favorite,notify,watchlist}.post = [ids]
- Title: id, post_type, title{english,local}, plot{english,local} (html entities), image.cover.{webp,jpg} url, image.poster.{webp,jpg}.large url, score.{30nama,imdb,...}.score, info.year, info.time.default (min), info.age, info.seasons, options.free_stream, genre[{name,slug}], crew.creator[0].name / director.
- End of pages: result.page == result.pages.
