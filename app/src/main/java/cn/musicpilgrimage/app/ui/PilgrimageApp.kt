package cn.musicpilgrimage.app.ui

import android.net.Uri
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.first
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.*
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun PilgrimageApp(session:SessionViewModel,requireAccount:Boolean=true) {
 val owner by session.signedIn.collectAsStateWithLifecycle()
 val ready by session.services.authReady.collectAsStateWithLifecycle()
 val account:MusicAccountViewModel=viewModel()
 if(!ready || requireAccount&&owner==null) {
  PilgrimageTheme {RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
   if(!ready)CircularProgressIndicator(Modifier.align(Alignment.Center),color=RoomColors.Navy)
   else RoomEmailLoginRoute(account)
  }}
 } else MusicSpaceContent(session,account,requireAccount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MusicSpaceContent(session:SessionViewModel,account:MusicAccountViewModel,requireAccount:Boolean) {
  PilgrimageTheme {
  val owner by session.signedIn.collectAsState();val recovery by session.recovery.collectAsState();val profile by session.profile.collectAsState()
  val openAuth by session.openAuth.collectAsState()
  key(Unit) {
   var authPending by rememberSaveable {mutableStateOf(false)}
   var pendingForm by rememberSaveable {mutableStateOf<String?>(null)}
   val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val route=entry?.destination?.route
   val navigationReady=entry!=null
   val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
   val context=LocalContext.current
   val wallBrowse=remember(context){RoomBrowseState(context)}
   DisposableEffect(wallBrowse){onDispose{wallBrowse.worldScene?.destroy();wallBrowse.worldScene=null}}
   val scope=rememberCoroutineScope()
   val preferences=remember(context){AppPreferences(context)}
   val pref by preferences.changes.collectAsStateWithLifecycle(AppPreferencesState())
   val editor:MusicProfileEditorViewModel=viewModel(key="music-profile-$owner")
   val draft by editor.state.collectAsStateWithLifecycle()
   val storedResults by account.performances.collectAsStateWithLifecycle()
   val sessionBusy by session.busy.collectAsStateWithLifecycle()
   val sessionError by session.error.collectAsStateWithLifecycle()
   var discardAction by remember{mutableStateOf<(() -> Unit)?>(null)}
   var savedNotice by remember{mutableStateOf<String?>(null)}
   var recordLeaveGuard by remember{mutableStateOf<(((()->Unit)->Unit))?>(null)}
   var recordSaving by remember{mutableStateOf(false)}
   var recordGuardOwner by remember{mutableStateOf<FormViewModel?>(null)}
   LaunchedEffect(route){if(route!="form/{kind}/{target}"){recordGuardOwner=null;recordLeaveGuard=null;recordSaving=false}}
   LaunchedEffect(savedNotice){if(savedNotice!=null){delay(2600);savedNotice=null}}
   val music:MusicSpaceViewModel=viewModel()
   val archive:MusicArchiveViewModel=viewModel()
   val map:MusicMapViewModel=viewModel()
   val trips:MusicTripViewModel=viewModel()
   val storedTrips by trips.state.collectAsStateWithLifecycle()
   val recentWork by music.recentWork.collectAsStateWithLifecycle()
   val musicCatalog by music.catalog.collectAsStateWithLifecycle()
   val storedMusicFavorites by music.favorites.collectAsStateWithLifecycle()
   val musicUndo by music.undo.collectAsStateWithLifecycle()
   val storedArchive by archive.state.collectAsStateWithLifecycle()
   val placeUndo by archive.undo.collectAsStateWithLifecycle()
   val sourceCatalog by session.services.catalog.collectAsStateWithLifecycle()
   var spaceOpen by remember {mutableStateOf(false)}
   var spaceOrigin by remember {mutableStateOf(Offset(360f,60f))}
   var selectedModule by rememberSaveable {mutableStateOf("room")}
   var moduleMotion by remember {mutableStateOf(false)}
   val performanceLibrary:PerformanceLibraryViewModel=viewModel()
   val performanceLibraryUi by performanceLibrary.state.collectAsStateWithLifecycle()
   LaunchedEffect(route,performanceLibraryUi.loaded){
    if(performanceLibraryUi.loaded&&(route=="performance"||route=="perform/{song}"))performanceLibrary.prepareAll()
   }
   val previewPlayable=LocalPerformancePreviews.current
   val performanceEntries=performanceLibraryUi.entries+previewPlayable.filter{preview->performanceLibraryUi.entries.none{it.song.id==preview.song.id}}
   val playable=performanceEntries.map{it.song}
   var performanceImmersive by remember{mutableStateOf(false)}
   val performanceArtwork:(SongPackage)->ReleaseArtwork?={song->
    musicCatalog.works.firstOrNull{it.title==song.title&&it.artist==song.artist}?.artwork
     ?: musicCatalog.releases.firstOrNull{it.title==song.title&&it.artist==song.artist}?.artwork
   }
   val libraryOwner=remember(owner){owner?.let{"user:$it"} ?: LibraryDatabase.guestOwner(context)}
   val tripState=storedTrips.takeIf{it.owner==libraryOwner}?:MusicTripState(libraryOwner,busy=true)
   val mapWork=musicCatalog.works.find{it.id==recentWork}?:musicCatalog.works.find{it.title=="最伟大的作品"&&it.artist=="周杰伦"}?:musicCatalog.works.firstOrNull()
   val tripCities=remember(sourceCatalog,tripState.trips){(musicMapCities(sourceCatalog,null).map{MusicTripCity(it.name,it.country)}+tripState.trips.flatMap{it.cities}).distinctBy{it.key}}
   var addTripPlace by rememberSaveable(owner){mutableStateOf<String?>(null)}
   val results=storedResults.filter{it.owner==libraryOwner&&it.difficulty==2}
   val musicFavorites=storedMusicFavorites.takeIf{it.owner==libraryOwner} ?: MusicFavorites()
   val archiveState=storedArchive.takeIf{it.owner==owner} ?: MusicArchiveState(owner=owner,loading=owner!=null)
   val navigate:(String)->Unit={moduleMotion=false;focus.clearFocus();keyboard?.hide();nav.navigate(it){launchSingleTop=true}}
   fun leaveProfile(action:()->Unit) {
    if(route=="form/{kind}/{target}"&&recordSaving)return
    if(route=="form/{kind}/{target}"&&recordLeaveGuard!=null){spaceOpen=false;recordLeaveGuard?.invoke(action);return}
    if(route=="edit-profile"&&draft.saving)return
    if(route=="edit-profile"&&draft.dirty){spaceOpen=false;discardAction=action}else action()
   }
   val back:()->Unit={leaveProfile{nav.popBackStack();Unit}}
   val login:()->Unit={authPending=true;session.clearError();session.notice.value=null;navigate("auth")}
   val openTrips:()->Unit={if(owner==null)login()else navigate("trips")}
   val addToTrip:(Place)->Unit={place->if(owner==null)login()else {trips.dismissError();addTripPlace=place.id}}
   val togglePlace:(String)->Unit={id->if(owner==null)login()else archive.togglePlace(id)}
   fun finishAuth() {
    authPending=false
    pendingForm=null
    nav.navigate("room"){popUpTo(nav.graph.id){inclusive=true};launchSingleTop=true}
   }
   val openForm:(String,String,ContentItem?)->Unit={kind,target,item->
    if(owner==null){pendingForm="form/$kind/${Uri.encode(target)}";login()}
    else if(profile?.optString("nickname").orEmpty().length<2){pendingForm="form/$kind/${Uri.encode(target)}";navigate("edit-profile")}
    else {nav.currentBackStackEntry?.savedStateHandle?.set("editing",item?.raw?.toString().orEmpty());navigate("form/$kind/${Uri.encode(target)}")}
   }
   val openProfile:(String)->Unit={if(owner==null)login() else navigate("profile/$it")}
   // Cold-start callbacks can arrive before NavHost installs its graph.
   LaunchedEffect(recovery,navigationReady){if(recovery&&navigationReady){authPending=true;navigate("auth")}}
   LaunchedEffect(openAuth,navigationReady){if(openAuth&&navigationReady){authPending=true;navigate("auth");session.openAuth.value=false}}
   LaunchedEffect(owner,navigationReady,route,recovery) {
    if(navigationReady&&owner!=null&&!recovery&&route=="auth")finishAuth()
   }
   val motion=LocalPhysicalMotion.current
   val openSpace:()->Unit={if(!(route=="edit-profile"&&draft.saving)&&!recordSaving){focus.clearFocus();keyboard?.hide();spaceOpen=true}}
   val chooseModule:(String)->Unit={path->
    leaveProfile {
    moduleMotion=true;spaceOpen=false
    focus.clearFocus();keyboard?.hide()
    val target=if(path=="catalogue")"room"else path
    if(target=="settings")nav.navigate(target){launchSingleTop=true}
    else {selectedModule=target;nav.navigate(target){popUpTo(nav.graph.findStartDestination().id);launchSingleTop=true}}
    }
   }
   val openRelease:(MusicRelease)->Unit={release->
    navigate("album/${Uri.encode(release.id)}")
   }
   var previousOwner by remember {mutableStateOf(owner)}
   LaunchedEffect(owner) {
    discardAction=null
    if(previousOwner!=owner&&route?.let{it.startsWith("form/")||it.startsWith("camera/")||it.startsWith("mine/")||it.startsWith("perform/")||it.startsWith("trip/")||it in listOf("trips","admin","submissions","edit-profile","account-email","account-publications")}==true) {
     pendingForm=null;nav.navigate("me"){popUpTo(nav.graph.findStartDestination().id);launchSingleTop=true}
    }
    previousOwner=owner
   }
   val roomTitle=when(route){"room"->"听踪";"catalogue"->"唱片架";"album/{id}"->"发行档案";"song/{id}"->"作品档案";"search","release-search/{id}"->"搜索";"place/{id}"->"地点档案";"place-community/{id}"->"地点社区";"trips"->"我的行程";"trip/{id}"->"行程档案";"music-favorites/{kind}"->"我的收藏";"library"->"资料册";"archive/{kind}"->if(entry?.arguments?.getString("kind")=="footprints")"我的足迹"else "地点收藏";"archive-more"->"更多记录";"performance"->"演奏";"me"->"我的";"account-profile"->"个人资料";"edit-profile"->"个人资料";"settings"->"设置";"account-email"->"邮箱账号";"account-privacy"->"位置与公开范围";"account-storage"->"本机草稿与图片";"account-about"->"关于听踪";"account-scores"->"演奏记录";"submissions"->"我的投稿";"admin"->"管理中心";"account-publications"->"我的发布与投稿";"account-badges"->"徽章";"profile/{id}"->"公开主页";"mine/{kind}"->mapOf("message" to "我的留言","visit" to "我的到访","recreation" to "我的复刻")[entry?.arguments?.getString("kind")];"form/{kind}/{target}"->entry?.arguments?.getString("kind")?.takeIf{it in listOf("message","visit","recreation")}?.let(::roomRecordTitle);else->null}
   val archiveRoute=route in listOf("library","archive/{kind}","music-favorites/{kind}","archive-more")
   val webDetailRoute=route in listOf("album/{id}","song/{id}","place/{id}","trip/{id}")
   val gameFullscreen=route=="perform/{song}"&&performanceImmersive
   DisposableEffect(gameFullscreen,context) {
    val window=(context as? Activity)?.window
    val controller=window?.let{WindowCompat.getInsetsController(it,it.decorView)}
    if(gameFullscreen){controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller?.hide(WindowInsetsCompat.Type.systemBars())}
    onDispose{if(gameFullscreen)controller?.show(WindowInsetsCompat.Type.systemBars())}
   }
   val wallSection=when(route){"room","catalogue"->0;"explore"->1;"performance"->2;"me"->3;else->null}
   RoomWall(Modifier.fillMaxSize().background(RoomColors.Wall).then(if(gameFullscreen||webDetailRoute)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),baseColor=if(archiveRoute)RoomColors.ArchiveWall else RoomColors.Wall) {
   if(wallSection!=null)RoomShelfWall(Modifier.matchParentSize())
   Scaffold(containerColor=Color.Transparent,contentWindowInsets=WindowInsets(0,0,0,0),
    modifier=if(spaceOpen)Modifier.clearAndSetSemantics{} else Modifier,
    bottomBar={if(wallSection!=null)SwissWallTabs(wallSection,chooseModule)},
    topBar={if(wallSection!=null&&wallSection!=1)SwissWallHeader(wallSection,{navigate("search")},openSpace,{spaceOrigin=it})else if(wallSection==null&&roomTitle!=null&&!webDetailRoute)RoomHeader(if(route=="room")"唱片架" else roomTitle,if(route in listOf("room","me"))null else back,
       if(route in listOf("room","catalogue")){ {navigate("search")} }else null,
       openSpace,{spaceOrigin=it},enabled=!(route=="edit-profile"&&draft.saving)&&!recordSaving,
       onTitleClick=if(route=="room"){ {navigate("catalogue")} }else null)}) {padding->
    CompositionLocalProvider(LocalRoomSpaceAction provides openSpace,LocalRoomSpaceOrigin provides {spaceOrigin=it}) {
    NavHost(navController=nav,startDestination="room",modifier=Modifier.padding(padding).consumeWindowInsets(padding),
     enterTransition={fadeIn(tween(if(motion)if(moduleMotion)160 else 180 else 0))+slideInHorizontally(tween(if(motion&&!moduleMotion)260 else 0)){if(motion&&!moduleMotion)it/16 else 0}},
     exitTransition={fadeOut(tween(if(motion)120 else 0))},
     popEnterTransition={fadeIn(tween(if(motion)180 else 0))+slideInHorizontally(tween(if(motion)260 else 0)){if(motion)-it/28 else 0}},
     popExitTransition={fadeOut(tween(if(motion)100 else 0))}) {
     composable("room"){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}
     composable("catalogue"){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}
     composable("album/{id}"){e->musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.let {release->
      RoomAlbum(release,release.id in musicFavorites.releases,{music.toggleFavorite(release.id,true)},{navigate("song/${Uri.encode(it.id)}")},{navigate("place/${Uri.encode(it)}")},onBack=back,onSearch={navigate("release-search/${Uri.encode(release.id)}")})
     }}
     composable("search"){RoomSearch(musicCatalog){navigate("song/${Uri.encode(it.id)}")}}
     composable("release-search/{id}"){e->RoomSearch(musicCatalog,musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.title.orEmpty()){navigate("song/${Uri.encode(it.id)}")}}
     composable("music-favorites/{kind}"){e->key(libraryOwner){RoomMusicFavorites(musicCatalog,musicFavorites,e.arguments?.getString("kind")?:"songs",{navigate("song/${Uri.encode(it.id)}")},openRelease,
       {id,release->placeUndo?.let{archive.dismissUndo(it.token)};music.removeFavorite(id,release)},{chooseModule("catalogue")},music.librarySync.collectAsStateWithLifecycle().value,music::syncLibrary,onPlaces={navigate("archive/places")})}}
     composable("song/{id}"){e->musicCatalog.works.find{it.id==e.arguments?.getString("id")}?.let {work->
      LaunchedEffect(work.id,libraryOwner){music.browseWork(work.id)}
      val play=playable.find{it.title==work.title&&it.artist==work.artist}
      RoomSong(work,musicCatalog.releases.find{it.id==work.releaseId}?.title.orEmpty(),work.id in musicFavorites.songs,{music.toggleFavorite(work.id)},
       {navigate("place/${Uri.encode(it)}")},play?.let{song->{navigate("perform/${Uri.encode(song.id)}")}},onBack=back,onRelease={navigate("album/${Uri.encode(work.releaseId)}")})
     }}
     composable("explore",enterTransition={EnterTransition.None},exitTransition={ExitTransition.None},popEnterTransition={EnterTransition.None},popExitTransition={ExitTransition.None}){SwissWorldWall(sourceCatalog,wallBrowse,archiveState.places.toSet(),archiveState.loading,{navigate("place/${Uri.encode(it)}")},togglePlace,openTrips,{navigate("archive/footprints")},addToTrip,{navigate("search")},back,archiveState.visits.map{it.location}.toSet())}
     composable("performance"){SwissPlayWall(performanceEntries,performanceArtwork,performanceLibraryUi.loaded,wallBrowse,performanceLibraryUi.downloads,{navigate("perform/${Uri.encode(it)}")},{performanceLibrary.prepareAll(retry=true)})}
     composable("perform/{song}"){e->
      val performanceEntry=performanceEntries.find{it.song.id==e.arguments?.getString("song")}
      if(performanceEntry!=null)key(libraryOwner){
        val song=performanceEntry.song
        val work=musicCatalog.works.firstOrNull{it.title==song.title&&it.artist==song.artist}
        val related:(()->Unit)?=work?.takeIf{it.places.isNotEmpty()}?.let{match->{navigate("song/${Uri.encode(match.id)}")}}
          ?: song.placeIds.firstOrNull()?.let{id->{navigate("place/${Uri.encode(id)}")}}
        if(performanceEntry.audioReady)PerformanceFlow(performanceEntry,libraryOwner,back,performanceArtwork(song),related,{performanceImmersive=it},
          importing=performanceLibraryUi.importing==song.id,importProgress=performanceLibraryUi.progress,
          importMessage=performanceLibraryUi.error ?: performanceLibraryUi.notice,directStart=true)
        else Column(Modifier.fillMaxSize()) {
          RoomHeader("正在准备音乐",onBack=back,onSpace=openSpace)
          Column(Modifier.fillMaxWidth().weight(1f).padding(26.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            RoomCover(performanceArtwork(song),song.title,Modifier.size(132.dp))
            Spacer(Modifier.height(24.dp));Text(song.title,color=SwissInk,style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            val status=performanceLibraryUi.downloads[song.id]
            Text(status?.label?:"正在后台准备音乐",color=RoomColors.Muted)
            Spacer(Modifier.height(16.dp));Text("准备好后直接进入演奏",color=RoomColors.Muted,style=MaterialTheme.typography.bodySmall)
            if(status?.failed==true)TextButton({performanceLibrary.prepareAll(retry=true)}){Text("重试",color=SwissRed)}
            else LinearProgressIndicator(progress={status?.progress?:0f},modifier=Modifier.fillMaxWidth().padding(top=24.dp),color=SwissRed)
          }
        }
      }
      else if(!performanceLibraryUi.loaded)CircularProgressIndicator(Modifier.padding(24.dp),color=RoomColors.Navy)
      else Text("这首作品正在校对，请返回曲目。",Modifier.padding(24.dp))
     }
     composable("library"){key(libraryOwner){RoomArchiveHome(musicCatalog,musicFavorites,archiveState,sourceCatalog.publicOnly().places,
       {kind->navigate(if(kind in listOf("songs","releases"))"music-favorites/$kind" else "archive/$kind")},
       {navigate("song/${Uri.encode(it.id)}")},openRelease,{navigate("place/${Uri.encode(it)}")},{navigate("archive-more")},openTrips)}}
     composable("archive/{kind}"){e->key(libraryOwner){RoomPlaceFavorites(archiveState,sourceCatalog.publicOnly().places,e.arguments?.getString("kind")?:"places",
       {navigate("place/${Uri.encode(it)}")},{id->musicUndo?.let{music.dismissUndo(it.token)};archive.removePlace(id)},login,archive::reload,
       {item->if(item.owner==owner)openForm("visit",item.location,item)},
       archive::deleteVisitContent,session.services,onMusic={navigate("music-favorites/$it")})}}
     composable("archive-more"){ArchiveOtherRecords(navigate)}
     composable("me"){
      SwissPersonalWall(profile?.takeIf{it.optString("id")==owner},owner!=null,session.services.accountAvatar(owner),musicCatalog.releases.firstOrNull()?.artwork,
       tripState.trips.size,results.maxOfOrNull{it.score},login,navigate,openSpace)
     }
     composable("account-profile"){
      val p=profile?.takeIf{it.optString("id")==owner}
      val placeCount=if(owner==null)0 else if(archiveState.loading&&p==null)null else p?.optJSONArray("favorites")?.length()?:archiveState.places.size
      val visitCount=if(owner==null)0 else if(archiveState.loading&&p==null)null else if(archiveState.visits.isNotEmpty())archiveState.visits.map{it.location}.distinct().size else p?.optInt("visit_count")?:0
      RoomMyAccount(p,owner!=null,session.services.accountEmail(owner),session.services.accountAvatar(owner),placeCount?.plus(musicFavorites.songs.size+musicFavorites.releases.size),visitCount,results.map{it.songId}.distinct().size,login,{navigate("edit-profile")},navigate,if(owner!=null)sessionError else null,{session.reloadProfile()})
     }
     composable("place/{id}"){e->sourceCatalog.publicOnly().places.find{it.id==e.arguments?.getString("id")}?.let {place->
      RoomPlaceDetail(place,mapWork,viewModel(key="place-$owner-${place.id}"),session,place.id in archiveState.places,archiveState.loading,{togglePlace(place.id)},login,openForm,archive::deleteVisitContent,{navigate("place-community/${Uri.encode(place.id)}")},{addToTrip(place)},onBack=back,onMap={wallBrowse.focusWorld(place.id);navigate("explore")})
     }?:Text("这个地点暂未收录，请返回重试。",Modifier.padding(24.dp))}
     composable("place-community/{id}"){e->sourceCatalog.publicOnly().places.find{it.id==e.arguments?.getString("id")}?.let {place->RoomPlaceCommunity(place,viewModel(key="community-$owner-${place.id}"),session,login,openForm,openProfile){navigate("mine/visit")}}}
     composable("trips"){if(owner==null)LaunchedEffect(Unit){login()}else key(libraryOwner){RoomTripList(tripState,libraryOwner,tripCities,trips){navigate("trip/${Uri.encode(it)}")}}}
     composable("trip/{id}"){e->if(owner==null)LaunchedEffect(Unit){login()}else key(libraryOwner){tripState.trips.find{it.id==e.arguments?.getString("id")}?.let{RoomTripDetailWeb(it,tripState,libraryOwner,trips,{id->navigate("place/${Uri.encode(id)}")},onBack=back,onDiscover={chooseModule("explore")},catalog=sourceCatalog.publicOnly())}?:Text(if(tripState.busy)"正在载入行程…"else "这个行程暂不可用，请返回重试。",Modifier.padding(24.dp))}}
     composable("auth"){if(recovery)AuthScreen(session,{authPending=false;pendingForm=null;back()})else RoomEmailLoginRoute(account,{account.otp.reset();authPending=false;pendingForm=null;back()})}
     composable("edit-profile"){
      LaunchedEffect(owner){editor.load(true)}
      RoomProfileEditor(draft,editor::nickname,editor::choosePhoto,editor::removePhoto,{
       editor.save {updated->if(updated.optString("id")==owner){session.profile.value=updated;val task=pendingForm;pendingForm=null;nav.popBackStack();if(task!=null)navigate(task)else savedNotice="个人资料已更新"}}
      },{editor.load(true)})
      BackHandler(enabled=!spaceOpen,onBack=back)
     }
     composable("settings"){RoomSettings(pref,owner!=null,sessionBusy,sessionError,
       {value->scope.launch{runCatching{preferences.setMuted(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},{value->scope.launch{runCatching{preferences.setReducedMotion(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},navigate,
       {session.signOut{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}})}
     listOf("email","privacy","storage","about").forEach{kind->composable("account-$kind"){
      RoomAccountInfo(kind,session.services.accountEmail(owner),owner!=null,sessionBusy,sessionError,
       {session.deleteAccount{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}},login)
     }}
     composable("account-scores"){RoomPerformanceRecords(results,playable){navigate("perform/${Uri.encode(it)}")}}
     composable("account-publications"){RoomAccountRecords(profile,navigate)}
     composable("account-badges"){RoomAccountBadges(profile)}
     composable("mine/{kind}"){e->val kind=e.arguments?.getString("kind").orEmpty();if(kind in listOf("message","visit","recreation"))RoomPersonalContentScreen(kind,viewModel(key="personal-$owner"),session,openForm,openProfile)else LaunchedEffect(Unit){nav.navigate("me"){popUpTo("room");launchSingleTop=true}}}
     composable("profile/{id}"){RoomPublicProfileScreen(it.arguments?.getString("id").orEmpty(),viewModel(key="public-$owner"),session,openForm,openProfile)}
     composable("submissions"){SubmissionsScreen(viewModel(key="submissions-$owner"),back){openForm("new_location","new",null)}}
     composable("admin"){AdminScreen(viewModel(key="admin-$owner"),back)}
     composable("form/{kind}/{target}"){e->
      val vm:FormViewModel=viewModel(key="form-$owner");val kind=e.arguments?.getString("kind").orEmpty();val target=e.arguments?.getString("target").orEmpty()
      val editing=remember(e,owner){nav.previousBackStackEntry?.savedStateHandle?.get<String>("editing")?.takeIf{it.isNotBlank()}?.let{ContentItem(JSONObject(it))}?.takeIf{it.owner==owner}}
      val photo by e.savedStateHandle.getStateFlow<String?>("camera_photo",null).collectAsState()
      LaunchedEffect(photo){photo?.let{vm.initialized.first{ready->ready};vm.photo(Uri.parse(it)).join();e.savedStateHandle["camera_photo"]=null}}
      if(kind in listOf("message","visit","recreation"))RoomRecordEditor(kind,target,editing,vm,back,
       {savedNotice=if(editing==null)"记录已保存"else "已保存修改";nav.popBackStack()},
       {navigate("camera/${Uri.encode(it)}")},
       {guard->if(guard!=null){recordGuardOwner=vm;recordLeaveGuard=guard}else if(recordGuardOwner===vm){recordGuardOwner=null;recordLeaveGuard=null;recordSaving=false}},
       {saving->if(recordGuardOwner===vm)recordSaving=saving})
      else if(kind in listOf("new_location","correction"))ContentForm(kind,target,editing,vm,back){navigate("camera/${Uri.encode(it)}")}
      else LaunchedEffect(Unit){nav.navigate("me"){popUpTo("room");launchSingleTop=true}}
      // Use the destination lifecycle, alongside NavHost's own back callback.
      BackHandler(enabled=!spaceOpen&&kind in listOf("message","visit","recreation"),onBack=back)
     }
     composable("camera/{scene}"){e->
      val catalog by session.services.catalog.collectAsState();val scene=catalog.places.flatMap{it.scenes}.find{it.id==e.arguments?.getString("scene")}
      CameraScreen(scene,back){uri->nav.previousBackStackEntry?.savedStateHandle?.set("camera_photo",uri.toString());nav.popBackStack()}
     }
    }
    }
   }
   if(!spaceOpen&&(archiveRoute||route in listOf("explore","place/{id}"))) {
    if(archiveRoute)musicUndo?.let{undo->RoomUndoNotice(undo.label,undo.token,{music.undoRemoval(undo.token)},{music.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
    placeUndo?.takeIf{it.owner==owner}?.let{undo->RoomUndoNotice("已取消地点收藏",undo.token,{archive.undoRemoval(undo.token)},{archive.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
   }
   if(savedNotice!=null&&!spaceOpen&&(route in listOf("me","place/{id}","place-community/{id}","profile/{id}","mine/{kind}")||archiveRoute))Surface(Modifier.align(Alignment.BottomCenter).padding(20.dp),color=RoomColors.Navy,shape=RoundedCornerShape(6.dp)){Text(savedNotice.orEmpty(),Modifier.padding(16.dp),color=RoomColors.Paper)}
   if(!spaceOpen&&route in listOf("explore","place/{id}")&&archiveState.error!=null)Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),color=RoomColors.Paper,shape=RoundedCornerShape(6.dp),shadowElevation=4.dp){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(archiveState.error.orEmpty(),Modifier.weight(1f),color=RoomColors.Ink);TextButton(archive::reload){Text("重试")}}}
   RoomSpaceMenu(spaceOpen,spaceOrigin,when {route in listOf("catalogue","album/{id}","song/{id}","search")->"catalogue";archiveRoute->"library";route=="room"->"room";else->selectedModule},motion,{spaceOpen=false},chooseModule)
   }
   sourceCatalog.publicOnly().places.find{it.id==addTripPlace}?.let{place->RoomAddToTripDialog(place,tripState,libraryOwner,tripCities,trips,{addTripPlace=null}){navigate("trip/${Uri.encode(it)}")}}
   if(discardAction!=null)RoomDiscardProfileDialog({discardAction=null},{val action=discardAction;discardAction=null;editor.discard();action?.invoke()})
   if(performanceLibraryUi.mobileConfirmation)AlertDialog(onDismissRequest=performanceLibrary::waitForWifi,
    title={Text("用移动网络准备音乐？")},text={
     val missing=performanceLibraryUi.entries.filterNot{it.audioReady}
     val bytes=missing.sumOf{it.song.recording?.files?.values?.sumOf{file->file.bytes}?:0L}
     Column {Text("共 ${missing.size} 首，约 ${bytes/1048576} MB。下载完成后可离线演奏。")
      Spacer(Modifier.height(12.dp));Text(missing.joinToString("、"){it.song.title},style=MaterialTheme.typography.bodySmall)}
    },confirmButton={TextButton(performanceLibrary::allowMobile){Text("下载全部")}},dismissButton={TextButton(performanceLibrary::waitForWifi){Text("等待 Wi-Fi")}})
  }
 }
}

