# Player / Single spec (from 30TV 3.2.3 decompile)
- single(post_id) -> result: Title fields {id, post_type, options{free_stream,...}, info{year,time(min),age}, title{english,local}, plot{english,local}, image{cover{jpg,webp}, poster{large..}}, genre[], score, air, seo} + cast[{name,as,id,image}], crew{creator[],director[]}, country[{name}], language[{name}], list, note, subtitle, network. No episodes here.
- Play: needs loggedIn && user_allowed_to_stream, or options.free_stream. -> VideoPlayer(id, free).
- user_list {action:'add', group: favorite|watchlist|notify, post_id} toggles. List: {action:'list', group, page, orderby:'add_list', order:'DESC'} -> result.posts/page/pages. Library: user_mylist -> {favorite[], watchlist[], notify[], downloadlist[]} of ids.
- stream(post_id, free_stream) -> result {info{post_id,title{local,english}}, image{cover{jpg,webp}}, continue_watching{time,file_id}|null, vast2?, hls:[{info{file_id,season,episode,episode_number,episode_name}, options{intro_start,intro_end,previously_start,previously_end,end,token} ("HH:MM:SS"), file{m3u8:[{label:'Auto'|'1080p'.., default:url, '2ch'?, '6ch'?}], subtitle{fa:vtt,en:vtt}}}]}
- isMovie = !hls[0].info.episode. Next/prev = hls index +-1. Resume: hls.find(file_id==cw.file_id), seek cw.time seconds.
- observer(post_id,file_id,user_id,time,duration,token=hls.options.token) every ~15s, seconds.
- Subtitles: VTT, fa / en / hybrid / none; size 40 default, white/yellow, bg black/semi/none.
- Audio tracks via player tracks (dub languages).
- vast2: optional VAST preroll ad.
