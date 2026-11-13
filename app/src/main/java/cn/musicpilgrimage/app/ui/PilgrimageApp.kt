package cn.musicpilgrimage.app.ui

import android.net.Uri
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.first
import android.app.Activity
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
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

private const val PlaceArchiveEntry = "place-archive-entry"
private class SavedNotice(val message: String, val destination: String?)

@Composable private fun NativeWallTab(section:Int,onSearch:()->Unit,content:@Composable ()->Unit) {
 Column(Modifier.fillMaxSize()) {
  SwissWallHeader(section,onSearch)
  Box(Modifier.fillMaxWidth().weight(1f)){content()}
 }
}

private fun isArchiveRoute(route:String?)=route=="archive/{kind}"||route=="music-favorites/{kind}"

private fun isWebDetailRoute(route:String?)=when(route) {
 "album/{id}","song/{id}","place/{id}","trip/{id}"->true
 else->false
}

@Composable private fun RoomRouteFrame(
 destination:NavBackStackEntry,performanceImmersive:Boolean,headerEnabled:Boolean,onBack:()->Unit,
 content:@Composable ()->Unit,
) {
 val route=destination.destination.route
 val active=LocalRouteActive.current
 val tab=wallSection(route)!=null
 val edgeToEdge=isWebDetailRoute(route)||route=="perform/{song}"&&performanceImmersive
 val title=when(route) {
  "search","release-search/{id}"->"搜索"
  "trips"->"我的行程"
  "archive/{kind}","music-favorites/{kind}"->"资料册"
  "account-profile","edit-profile","profile/{id}"->"个人资料"
  "settings"->"设置"
  "account-email"->"邮箱账号"
  "account-privacy"->"位置与公开范围"
  "account-storage"->"本机草稿与图片"
  "account-about"->"关于听踪"
  "submissions"->"我的投稿"
  "admin"->"管理中心"
  "form/{kind}/{target}"->destination.arguments?.getString("kind")
   ?.takeIf{it in listOf("message","visit","recreation")}?.let(::roomRecordTitle)
  else->null
 }
 RoomWall(Modifier.fillMaxSize(),baseColor=if(isArchiveRoute(route))RoomColors.ArchiveWall else RoomColors.Wall) {
  val outgoing=if(active)Modifier else Modifier.semantics{hideFromAccessibility()}
  Box(Modifier.fillMaxSize().then(outgoing).then(if(edgeToEdge)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
   if(tab)RoomShelfWall(Modifier.matchParentSize())
   Column(Modifier.fillMaxSize()) {
    if(title!=null)RoomHeader(title,onBack=onBack,onSpace={},enabled=headerEnabled)
    Box(Modifier.fillMaxWidth().weight(1f)){content()}
    if(tab)Spacer(Modifier.height(SwissWallTabsHeight))
   }
  }
 }
}

@Composable fun PilgrimageApp(session:SessionViewModel,requireAccount:Boolean=true) = PilgrimageTheme {
 val owner by session.signedIn.collectAsStateWithLifecycle()
 val ready by session.services.authReady.collectAsStateWithLifecycle()
 val account:MusicAccountViewModel=viewModel()
 val motion=LocalPhysicalMotion.current
 var showedSignIn by remember{mutableStateOf(false)}
 if(!ready || requireAccount&&owner==null) {
  if(ready)SideEffect{showedSignIn=true}
  RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
   if(!ready) {
    if(motion)CircularProgressIndicator(Modifier.align(Alignment.Center),color=RoomColors.Navy)
    else Text("正在打开听踪…",Modifier.align(Alignment.Center),color=RoomColors.Muted)
   }
   else RoomEmailLoginRoute(account)
  }
 } else if(showedSignIn) {
  val visible=remember{MutableTransitionState(false).apply{targetState=true}}
  AnimatedVisibility(visibleState=visible,enter=if(motion)AppRouteMotion.Detail.enter()else EnterTransition.None) {
   MusicSpaceContent(session,account,requireAccount)
  }
 } else MusicSpaceContent(session,account,requireAccount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MusicSpaceContent(session:SessionViewModel,account:MusicAccountViewModel,requireAccount:Boolean) {
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
   val sessionBusy by session.busy.collectAsStateWithLifecycle()
   val sessionError by session.error.collectAsStateWithLifecycle()
   var discardAction by remember{mutableStateOf<(() -> Unit)?>(null)}
   var savedNotice by remember{mutableStateOf<SavedNotice?>(null)}
   fun showSaved(message:String) {
    savedNotice=SavedNotice(message,nav.currentBackStackEntry?.id)
   }
   var recordLeaveGuard by remember{mutableStateOf<(((()->Unit)->Unit))?>(null)}
   var recordSaving by remember{mutableStateOf(false)}
   var recordGuardOwner by remember{mutableStateOf<FormViewModel?>(null)}
   LaunchedEffect(route){if(route!="form/{kind}/{target}"){recordGuardOwner=null;recordLeaveGuard=null;recordSaving=false}}
   LaunchedEffect(savedNotice,entry?.id) {
    val notice=savedNotice ?: return@LaunchedEffect
    if(notice.destination==entry?.id)delay(2600)
    if(savedNotice===notice)savedNotice=null
   }
   val music:MusicSpaceViewModel=viewModel()
   val archive:MusicArchiveViewModel=viewModel()
   val map:MusicMapViewModel=viewModel()
   val trips:MusicTripViewModel=viewModel()
   val storedTrips by trips.state.collectAsStateWithLifecycle()
   val musicCatalog by music.catalog.collectAsStateWithLifecycle()
   val storedMusicFavorites by music.favorites.collectAsStateWithLifecycle()
   val musicUndo by music.undo.collectAsStateWithLifecycle()
   val storedArchive by archive.state.collectAsStateWithLifecycle()
   val placeUndo by archive.undo.collectAsStateWithLifecycle()
   val sourceCatalog by session.services.catalog.collectAsStateWithLifecycle()
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
   val tripCities=remember(sourceCatalog,tripState.trips){(musicMapCities(sourceCatalog,null).map{MusicTripCity(it.name,it.country)}+tripState.trips.flatMap{it.cities}).distinctBy{it.key}}
   var addTripPlace by rememberSaveable(owner){mutableStateOf<String?>(null)}
   var addTripEntry by rememberSaveable(owner){mutableStateOf<String?>(null)}
   fun closeAddTrip() {addTripPlace=null;addTripEntry=null}
   LaunchedEffect(entry?.id) {if(addTripEntry!=null&&addTripEntry!=entry?.id)closeAddTrip()}
   val musicFavorites=storedMusicFavorites.takeIf{it.owner==libraryOwner} ?: MusicFavorites()
   val archiveState=storedArchive.takeIf{it.owner==owner} ?: MusicArchiveState(owner=owner,loading=owner!=null)
   fun navigateTo(path:String,placeEntry:Bundle?=null) {
    if(runCatching{nav.getBackStackEntry(path).id}.getOrNull()==nav.currentBackStackEntry?.id)return
    focus.clearFocus();keyboard?.hide();nav.navigate(path){launchSingleTop=true}
    placeEntry?.let{nav.currentBackStackEntry?.savedStateHandle?.set(PlaceArchiveEntry,it)}
   }
   val navigate:(String)->Unit={navigateTo(it)}
   fun followMusicArchiveLink(source:NavBackStackEntry,target:String,origin:Bundle?=null) {
    if(nav.currentBackStackEntry!=source)return
    if(runCatching{nav.getBackStackEntry(target)}.getOrNull()!=null) {
     nav.popBackStack(target,inclusive=false)
    }else navigateTo(target,origin)
   }
   fun openArchivePlace(source:NavBackStackEntry,id:String,links:List<MusicPlaceLink>) {
    if(nav.currentBackStackEntry!=source)return
    val link=links.find{it.place.id==id}?:return
    val origin=Bundle().apply {
     putStringArrayList("scenes",ArrayList(link.scenes.map(Scene::id)))
    }
    followMusicArchiveLink(source,"place/${Uri.encode(id)}",origin)
   }
   fun leaveProfile(action:()->Unit) {
    if(route=="form/{kind}/{target}"&&recordSaving)return
    if(route=="form/{kind}/{target}"&&recordLeaveGuard!=null){recordLeaveGuard?.invoke(action);return}
    if(route=="edit-profile"&&draft.saving)return
    if(route=="edit-profile"&&draft.dirty){discardAction=action}else action()
   }
   val back:()->Unit={
    if(addTripPlace!=null&&addTripEntry==nav.currentBackStackEntry?.id) {
     if(!tripState.busy)closeAddTrip()
    }
    else leaveProfile {nav.popBackStack()}
   }
   val login:()->Unit={authPending=true;session.clearError();session.notice.value=null;navigate("auth")}
   val openTrips:()->Unit={if(owner==null)login()else navigate("trips")}
   val addToTrip:(Place)->Unit={place->if(owner==null)login()else {
    trips.dismissError();addTripEntry=nav.currentBackStackEntry?.id;addTripPlace=place.id
   }}
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
   val chooseModule:(String)->Unit={path->
    leaveProfile {
     val target=if(path=="catalogue")"room"else path
     if(wallSection(nav.currentDestination?.route)!=wallSection(target)) {
      focus.clearFocus();keyboard?.hide()
      if(target=="room")nav.popBackStack("room",inclusive=false,saveState=true)
      else nav.navigate(target) {
       popUpTo(nav.graph.findStartDestination().id){saveState=true}
       launchSingleTop=true
       restoreState=true
      }
     }
    }
   }
   val openRelease:(MusicRelease)->Unit={release->
    navigate("album/${Uri.encode(release.id)}")
   }
   val profileScreen:@Composable (String)->Unit={id->
    if(id.isBlank())LaunchedEffect(Unit){login()}
    else {
     val own=id==owner
     val p=profile?.takeIf{own&&it.optString("id")==id}
     val management:(@Composable ()->Unit)?=if(own) {{
      val placeCount=archiveState.takeIf{it.owner==id&&!it.loading}?.places?.size
       ?:p?.optJSONArray("favorites")?.length()
      val visitCount=if(archiveState.loading&&p==null)null else if(archiveState.visits.isNotEmpty())archiveState.visits.map{it.location}.distinct().size else p?.optInt("visit_count")?:0
      RoomProfileManagement(session.services.accountEmail(id),placeCount?.plus(musicFavorites.songs.size+musicFavorites.releases.size),
       visitCount,p?.optString("status")=="disabled",{navigate("edit-profile")},navigate,
       sessionError,{session.reloadProfile()})
     }} else null
     RoomProfileScreen(id,viewModel(key="profile-$owner-$id"),session,openForm,openProfile,
      ownerName=if(own)p?.optString("nickname")?.takeIf(String::isNotBlank)?:"音乐旅人" else null,
      ownerAvatar=if(own)session.services.accountAvatar(id) else null,ownerManagement=management)
    }
   }
   var previousOwner by remember {mutableStateOf(owner)}
   LaunchedEffect(owner) {
    discardAction=null
    if(previousOwner!=owner&&route?.let{it.startsWith("form/")||it.startsWith("camera/")||it.startsWith("mine/")||it.startsWith("perform/")||it.startsWith("trip/")||it in listOf("trips","admin","submissions","edit-profile","account-email","account-publications")}==true) {
     pendingForm=null;nav.navigate("me"){popUpTo(nav.graph.findStartDestination().id);launchSingleTop=true}
    }
    previousOwner=owner
   }
   val archiveRoute=isArchiveRoute(route)
   LaunchedEffect(archiveRoute) {
    if(!archiveRoute) {
     music.undo.value?.let{music.dismissUndo(it.token)}
     archive.undo.value?.let{archive.dismissUndo(it.token)}
    }
   }
   val webDetailRoute=isWebDetailRoute(route)
   val gameFullscreen=route=="perform/{song}"&&performanceImmersive
   DisposableEffect(gameFullscreen,context) {
    val window=(context as? Activity)?.window
    val controller=window?.let{WindowCompat.getInsetsController(it,it.decorView)}
    if(gameFullscreen){controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller?.hide(WindowInsetsCompat.Type.systemBars())}
    onDispose{if(gameFullscreen)controller?.show(WindowInsetsCompat.Type.systemBars())}
   }
   val wallSection=wallSection(route)
   var lastWallSection by remember {mutableIntStateOf(wallSection?:0)}
   SideEffect {wallSection?.let{lastWallSection=it}}

   fun NavGraphBuilder.page(
    path:String,arguments:List<NamedNavArgument> = emptyList(),
    content:@Composable AnimatedContentScope.(NavBackStackEntry)->Unit,
   ) {
    composable(path,arguments=arguments){destination->
     CompositionLocalProvider(LocalRouteActive provides (entry?.id==destination.id),
      LocalRouteIsCurrent provides {nav.currentBackStackEntry?.id==destination.id}) {
      RoomRouteFrame(destination,performanceImmersive,!(path=="edit-profile"&&draft.saving)&&!recordSaving,back) {
       content(destination)
      }
     }
    }
   }

   RoomWall(Modifier.fillMaxSize()) {
    NavHost(navController=nav,startDestination="room",modifier=Modifier.fillMaxSize(),
     enterTransition={
      if(motion)routeMotion(initialState.destination.route,targetState.destination.route).enter(initialAlpha=.55f)
      else EnterTransition.None
     },
     exitTransition={if(motion)ExitTransition.KeepUntilTransitionsFinished else ExitTransition.None},
     popEnterTransition={
      if(motion)routeMotion(initialState.destination.route,targetState.destination.route).enter(initialAlpha=.8f)
      else EnterTransition.None
     },
     popExitTransition={ExitTransition.None}) {
     page("room"){NativeWallTab(0,{navigate("search")}){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}}
     page("catalogue"){NativeWallTab(0,{navigate("search")}){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}}
     page("album/{id}",arguments=listOf(navArgument("id"){type=NavType.StringType})){e->musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.let {release->
      RoomAlbum(release,release.id in musicFavorites.releases,{music.toggleFavorite(release.id,true)},
       {followMusicArchiveLink(e,"song/${Uri.encode(it.id)}")},{openArchivePlace(e,it,release.places)},
       onBack=back,onSearch={navigate("release-search/${Uri.encode(release.id)}")})
     }}
     page("search"){RoomSearch(musicCatalog){navigate("song/${Uri.encode(it.id)}")}}
     page("release-search/{id}"){e->RoomSearch(musicCatalog,musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.title.orEmpty()){navigate("song/${Uri.encode(it.id)}")}}
     composable("music-favorites/{kind}"){e->
      val kind=if(e.arguments?.getString("kind")=="releases")"releases" else "songs"
      LaunchedEffect(kind){nav.navigate("archive/$kind"){popUpTo("music-favorites/{kind}"){inclusive=true};launchSingleTop=true}}
     }
     page("song/{id}",arguments=listOf(navArgument("id"){type=NavType.StringType})){e->musicCatalog.works.find{it.id==e.arguments?.getString("id")}?.let {work->
      LaunchedEffect(work.id,libraryOwner){music.browseWork(work.id)}
      val play=playable.find{it.title==work.title&&it.artist==work.artist}
      RoomSong(work,musicCatalog.releases.find{it.id==work.releaseId}?.title.orEmpty(),work.id in musicFavorites.songs,{music.toggleFavorite(work.id)},
       {openArchivePlace(e,it,work.places)},play?.let{song->{navigate("perform/${Uri.encode(song.id)}")}},
       onBack=back,onRelease={followMusicArchiveLink(e,"album/${Uri.encode(work.releaseId)}")})
     }}
     page("explore"){e->
      SwissWorldWall(sourceCatalog,wallBrowse,archiveState.places.toSet(),archiveState.loading,
       {navigate("place/${Uri.encode(it)}")},togglePlace,openTrips,{navigate("archive/footprints")},addToTrip,
       {navigate("search")},back,archiveState.visits.map{it.location}.toSet(),active=entry==e)
     }
     page("performance"){NativeWallTab(2,{navigate("search")}){
      SwissPlayWall(performanceEntries,performanceArtwork,performanceLibraryUi.loaded,wallBrowse,performanceLibraryUi.downloads,{navigate("perform/${Uri.encode(it)}")},{performanceLibrary.prepareAll(retry=true)})
     }}
     page("perform/{song}"){e->
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
          RoomHeader("正在准备音乐",onBack=back,onSpace={})
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
     // Resolve saved navigation stacks from versions that still had these screens.
     listOf("library" to "me","archive-more" to "archive/footprints","account-scores" to "me","account-publications" to "submissions","account-badges" to "me").forEach { (old,destination)->
      composable(old){LaunchedEffect(Unit){nav.navigate(destination){popUpTo(old){inclusive=true};launchSingleTop=true}}}
     }
     page("archive/{kind}"){e->key(libraryOwner) {
      val kind=e.arguments?.getString("kind").orEmpty()
      val places=sourceCatalog.publicOnly().places
      val openPlace:(String)->Unit={navigate("place/${Uri.encode(it)}")}
      val initialKind=when(kind){"songs","releases","places"->kind;else->"footprints"}
      val initialFootprint=when(kind){"recreations","messages"->kind;else->"visits"}
      RoomArchive(initialKind,music={selection->
       RoomMusicFavorites(musicCatalog,musicFavorites,selection,{navigate("song/${Uri.encode(it.id)}")},openRelease,
        {id,release->placeUndo?.let{archive.dismissUndo(it.token)};music.removeFavorite(id,release)},
        {chooseModule("catalogue")},music.librarySync.collectAsStateWithLifecycle().value,music::syncLibrary)
      },places={
       RoomPlaceFavorites(archiveState,places,openPlace,{id->musicUndo?.let{music.dismissUndo(it.token)};archive.removePlace(id)},login,archive::reload)
      },footprints={
       RoomFootprints(archiveState,places,initialFootprint,openPlace,login,archive::reload,
        {item->if(item.owner==owner)openForm("visit",item.location,item)},archive::deleteVisitContent,session.services) { recordKind->
         RoomPersonalContentScreen(recordKind,viewModel(key="footprint-$recordKind-$owner"),session,openForm,openProfile)
        }
      })
     }}
     page("me"){
      NativeWallTab(3,{navigate("search")}){
       SwissPersonalWall(profile?.takeIf{it.optString("id")==owner},owner!=null,session.services.accountAvatar(owner),login,openTrips,navigate)
      }
     }
     page("account-profile"){profileScreen(owner.orEmpty())}
     page("place/{id}"){e->sourceCatalog.publicOnly().places.find{it.id==e.arguments?.getString("id")}?.let {place->
      BackHandler(enabled=entry?.id==e.id,onBack=back)
      val origin=e.savedStateHandle.get<Bundle>(PlaceArchiveEntry)
      RoomPlaceDetail(place,origin?.getStringArrayList("scenes").orEmpty().toSet(),
       viewModel(key="place-$owner-${place.id}"),session,place.id in archiveState.places,archiveState.loading,{togglePlace(place.id)},login,openForm,openProfile,
       {addToTrip(place)},musicCatalog,
       onMusicArchive={target->followMusicArchiveLink(e,"${if(target.isRelease)"album" else "song"}/${Uri.encode(target.id)}")},
       onBack=back,onMap={wallBrowse.focusWorld(place.id);navigate("explore")})
     }?:Text("这个地点暂未收录，请返回重试。",Modifier.padding(24.dp))}
     page("trips"){if(owner==null)LaunchedEffect(Unit){login()}else key(libraryOwner){RoomTripList(tripState,libraryOwner,tripCities,trips){navigate("trip/${Uri.encode(it)}")}}}
     page("trip/{id}"){e->if(owner==null)LaunchedEffect(Unit){login()}else key(libraryOwner){tripState.trips.find{it.id==e.arguments?.getString("id")}?.let{RoomTripDetailWeb(it,tripState,libraryOwner,trips,{id->navigate("place/${Uri.encode(id)}")},onBack=back,onDiscover={chooseModule("explore")},catalog=sourceCatalog.publicOnly())}?:Text(if(tripState.busy)"正在载入行程…"else "这个行程暂不可用，请返回重试。",Modifier.padding(24.dp))}}
     page("auth"){if(recovery)AuthScreen(session,{authPending=false;pendingForm=null;back()})else RoomEmailLoginRoute(account,{account.otp.reset();authPending=false;pendingForm=null;back()})}
     page("edit-profile"){e->
      LaunchedEffect(owner){editor.load(true)}
      RoomProfileEditor(draft,editor::nickname,editor::choosePhoto,editor::removePhoto,{
       editor.save {updated->if(updated.optString("id")==owner){session.profile.value=updated;val task=pendingForm;pendingForm=null;nav.popBackStack();if(task!=null)navigate(task)else showSaved("个人资料已更新")}}
      },{editor.load(true)})
      BackHandler(enabled=entry?.id==e.id,onBack=back)
     }
     page("settings"){RoomSettings(pref,owner!=null,sessionBusy,sessionError,
       {value->scope.launch{runCatching{preferences.setMuted(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},{value->scope.launch{runCatching{preferences.setReducedMotion(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},navigate,
       {session.signOut{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}})}
     listOf("email","privacy","storage","about").forEach{kind->page("account-$kind"){
      RoomAccountInfo(kind,session.services.accountEmail(owner),owner!=null,sessionBusy,sessionError,
       {session.deleteAccount{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}},login)
     }}
     composable("mine/{kind}"){e->
      val kind=e.arguments?.getString("kind")
      LaunchedEffect(kind) {
       val destination=when(kind){"visit"->"archive/footprints";"recreation"->"archive/recreations";"message"->"archive/messages";else->"me"}
       nav.navigate(destination){popUpTo("mine/{kind}"){inclusive=true};launchSingleTop=true}
      }
     }
     page("profile/{id}"){profileScreen(it.arguments?.getString("id").orEmpty())}
     page("submissions"){
      val canManage=owner!=null&&profile?.optString("id")==owner&&profile?.optString("role")=="admin"
      RoomSubmissions(viewModel(key="submissions-$owner"),onAdmin=if(canManage){{navigate("admin")}}else null){openForm("new_location","new",null)}
     }
     page("admin"){AdminScreen(viewModel(key="admin-$owner"),back)}
     page("form/{kind}/{target}"){e->
      val vm:FormViewModel=viewModel(key="form-$owner");val kind=e.arguments?.getString("kind").orEmpty();val target=e.arguments?.getString("target").orEmpty()
      val editing=remember(e,owner){nav.previousBackStackEntry?.savedStateHandle?.get<String>("editing")?.takeIf{it.isNotBlank()}?.let{ContentItem(JSONObject(it))}?.takeIf{it.owner==owner}}
      val photo by e.savedStateHandle.getStateFlow<String?>("camera_photo",null).collectAsState()
      LaunchedEffect(photo){photo?.let{vm.initialized.first{ready->ready};vm.photo(Uri.parse(it)).join();e.savedStateHandle["camera_photo"]=null}}
      if(kind in listOf("message","visit","recreation"))RoomRecordEditor(kind,target,editing,vm,back,
       {if(nav.currentBackStackEntry?.id==e.id){nav.popBackStack();showSaved(if(editing==null)"记录已保存"else "已保存修改")}},
       {navigate("camera/${Uri.encode(it)}")},
       {guard->if(guard!=null){recordGuardOwner=vm;recordLeaveGuard=guard}else if(recordGuardOwner===vm){recordGuardOwner=null;recordLeaveGuard=null;recordSaving=false}},
       {saving->if(recordGuardOwner===vm)recordSaving=saving})
      else if(kind in listOf("new_location","correction"))RoomSubmissionForm(kind,target,editing,vm,back)
      else LaunchedEffect(Unit){nav.navigate("me"){popUpTo("room");launchSingleTop=true}}
      // Use the destination lifecycle, alongside NavHost's own back callback.
      BackHandler(enabled=entry?.id==e.id&&kind in listOf("message","visit","recreation"),onBack=back)
     }
     page("camera/{scene}"){e->
      val catalog by session.services.catalog.collectAsState();val scene=catalog.places.flatMap{it.scenes}.find{it.id==e.arguments?.getString("scene")}
      CameraScreen(scene,back){uri->nav.previousBackStackEntry?.savedStateHandle?.set("camera_photo",uri.toString());nav.popBackStack()}
     }
    }
    AnimatedVisibility(
     visible=wallSection!=null,
     modifier=Modifier.align(Alignment.BottomCenter)
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom)),
     enter=if(motion)AppRouteMotion.Tab.enter(.55f)else EnterTransition.None,
     exit=if(motion)fadeOut(tween(WallTabFadeMillis,easing=appMotionEasing))else ExitTransition.None,
    ) {
     SwissWallTabs(wallSection?:lastWallSection,chooseModule,enabled=wallSection!=null)
    }
    Box(Modifier.matchParentSize().then(if(gameFullscreen||webDetailRoute)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
   if(archiveRoute) {
    musicUndo?.let{undo->RoomUndoNotice(undo.label,undo.token,{music.undoRemoval(undo.token)},{music.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
    placeUndo?.takeIf{it.owner==owner}?.let{undo->RoomUndoNotice("已取消地点收藏",undo.token,{archive.undoRemoval(undo.token)},{archive.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
   }
   if(route in listOf("explore","place/{id}")&&archiveState.error!=null)Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),color=RoomColors.Paper,shape=RoundedCornerShape(6.dp),shadowElevation=4.dp){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(archiveState.error.orEmpty(),Modifier.weight(1f),color=RoomColors.Ink);TextButton(archive::reload){Text("重试")}}}
   }
   val notice=savedNotice?.takeIf{it.destination==entry?.id}
   AnimatedVisibility(
    visible=notice!=null,
    modifier=Modifier.align(Alignment.BottomCenter)
     .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
     .padding(bottom=if(wallSection!=null)SwissWallTabsHeight+12.dp else 20.dp),
    enter=if(motion)AppRouteMotion.Tab.enter(.55f)else EnterTransition.None,
    exit=if(motion)fadeOut(tween(90,easing=appMotionEasing))else ExitTransition.None,
   ) {
    var message by remember{mutableStateOf(notice?.message.orEmpty())}
    SideEffect {if(notice!=null)message=notice.message}
    Surface(color=RoomColors.Navy,shape=RoundedCornerShape(6.dp)) {
     Text(notice?.message?:message,Modifier.padding(horizontal=16.dp,vertical=12.dp)
      .semantics{liveRegion=LiveRegionMode.Polite},color=RoomColors.Paper)
    }
   }
   }
   addTripPlace?.takeIf{addTripEntry==entry?.id}?.let { id ->
    sourceCatalog.publicOnly().places.find{it.id==id}
   }?.let{place->
    RoomAddToTripDialog(place,tripState,libraryOwner,tripCities,trips,onAdded={
     val onSource=nav.currentBackStackEntry?.id==addTripEntry
     closeAddTrip()
     if(onSource)showSaved("已加入行程")
    },onClose=::closeAddTrip)
   }
   if(discardAction!=null)RoomDiscardProfileDialog({discardAction=null},{val action=discardAction;discardAction=null;editor.discard();action?.invoke()})
   if(performanceLibraryUi.mobileConfirmation)AlertDialog(onDismissRequest=performanceLibrary::waitForWifi,
    title={Text("用移动网络准备音乐？")},text={
     val missing=performanceLibraryUi.entries.filterNot{it.downloadReady}
     val bytes=missing.sumOf{it.song.recording?.downloadFile?.second?.bytes?:0L}
     Column {Text("共 ${missing.size} 首，约 ${bytes/1048576} MB。下载完成后可离线演奏。")
      Spacer(Modifier.height(12.dp));Text(missing.joinToString("、"){it.song.title},style=MaterialTheme.typography.bodySmall)}
    },confirmButton={TextButton(performanceLibrary::allowMobile){Text("下载全部")}},dismissButton={TextButton(performanceLibrary::waitForWifi){Text("等待 Wi-Fi")}})
 }
}

