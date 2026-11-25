package cn.musicpilgrimage.app.ui

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.first
import android.app.Activity
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val PlaceArchiveEntry = "place-archive-entry"
private class SavedNotice(val message: String, val destination: String?)

private val RouteParameterPattern = Regex("""\{(\w+)\}""")

internal fun NavGraphBuilder.appDestination(
 route: String,
 content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
 // Navigation compares back-stack argument values only when their types are declared.
 val arguments = RouteParameterPattern.findAll(route).map { match ->
  navArgument(match.groupValues[1]) { type = NavType.StringType }
 }.toList()
 composable(route,arguments=arguments,content=content)
}

@Composable private fun NativeWallTab(
 section:Int,
 onSearch:()->Unit,
 beforeSearch:@Composable ()->Unit={},
 content:@Composable ()->Unit,
) {
 Column(Modifier.fillMaxSize()) {
  SwissWallHeader(section,onSearch,beforeSearch=beforeSearch)
  Box(Modifier.fillMaxWidth().weight(1f)){content()}
 }
}

private fun isArchiveRoute(route:String?)=route=="archive/{kind}"||route=="music-favorites/{kind}"

private fun usesEdgeToEdgeArchiveLayout(route:String?)=when(route) {
 "album/{id}","song/{id}","place/{id}","trip/{id}"->true
 else->false
}

@Composable private fun RoomRouteFrame(
 destination:NavBackStackEntry,performanceImmersive:Boolean,headerEnabled:Boolean,onBack:()->Unit,
 collapsingTitleVisible:Boolean=true,
 onChooseModule:((String)->Unit)?=null,
 selectedWallTab:Int?=null,
 content:@Composable ()->Unit,
) {
 val route=destination.destination.route
 val active=LocalRouteActive.current
 val motion=LocalPhysicalMotion.current
 val tabIndex=wallSection(route)
 val tab=tabIndex!=null
 val edgeToEdge=usesEdgeToEdgeArchiveLayout(route)||route=="perform/{song}"&&performanceImmersive
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
 val headerTitleAlpha by animateFloatAsState(
  if(collapsingTitleVisible)1f else 0f,
  tween(if(motion)160 else 0,easing=AppNavEasing),
  label="header-title-alpha",
 )
 RoomWall(Modifier.fillMaxSize(),baseColor=if(isArchiveRoute(route))RoomColors.ArchiveWall else RoomColors.Wall) {
  val outgoing=if(active)Modifier else Modifier.semantics{hideFromAccessibility()}
  Box(Modifier.fillMaxSize().then(outgoing).then(if(edgeToEdge)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
   if(tab)RoomShelfWall(Modifier.matchParentSize())
   Column(Modifier.fillMaxSize()) {
    if(title!=null)RoomHeader(title,onBack=onBack,onSpace={},enabled=headerEnabled,titleAlpha=headerTitleAlpha,titleAccessibilityVisible=collapsingTitleVisible)
    Box(Modifier.fillMaxWidth().weight(1f)){content()}
    if(tabIndex!=null) {
     SwissWallTabs(selectedWallTab?:tabIndex,onChooseModule?:{},enabled=active,includeTestTags=active)
    }
   }
  }
 }
}

@Composable private fun AlbumArchiveRoute(
 entry:NavBackStackEntry,
 release:MusicRelease,
 nav:NavHostController,
 coverCoordinator:CoverTransitionCoordinator,
 allowBackHandler:Boolean,
 onStartInteractivePop:(Float)->Unit,
 content:@Composable (onBack:()->Unit)->Unit,
) {
 val encodedId=remember(release.id){Uri.encode(release.id)}
 val coverKey=remember(release.id){"release:${release.id}"}
 val albumRoute=remember(encodedId){"album/$encodedId"}
 val identity=remember(release.id){"album:${release.id}"}
 val motion=LocalPhysicalMotion.current
 val orientation=LocalConfiguration.current.orientation
 val scope=rememberCoroutineScope()
 val prevShelfEntryId=remember(entry.id) {
  nav.previousBackStackEntry?.takeIf{wallSection(it.destination.route)!=null}?.id
 }
 SideEffect {
  if(nav.currentBackStackEntry?.id==entry.id)coverCoordinator.bindDestinationEntry(albumRoute,coverKey,entry.id)
 }
 LaunchedEffect(motion) {
  if(!motion&&coverCoordinator.isMorphingEnter(coverKey)) {
   coverCoordinator.cancelAll()
  }
 }
 var interactiveGen by remember(entry.id){mutableStateOf<Long?>(null)}
 var gestureInFlight by remember(entry.id){mutableStateOf(false)}
 var commitInFlight by remember(entry.id){mutableStateOf(false)}
 var routeWidthPx by remember(entry.id){mutableFloatStateOf(0f)}
 val swipeProgress=remember(entry.id){Animatable(0f)}

 fun commitReturn(fromGesture:Boolean) {
  if(commitInFlight||nav.currentBackStackEntry?.id!=entry.id)return
  commitInFlight=true
  gestureInFlight=false
  val activeGen=interactiveGen
  interactiveGen=null
  val usedMorph=if(activeGen!=null) {
   coverCoordinator.commitInteractiveReturn(activeGen)
  } else {
   coverCoordinator.beginReturnSession(
    key=coverKey,
    route=albumRoute,
    identity=identity,
    physicalMotion=motion,
    sourceNavEntryId=entry.id,
    targetNavEntryId=prevShelfEntryId,
    interactive=false,
    orientation=orientation,
   )!=null
  }
  onStartInteractivePop(if(!usedMorph&&fromGesture)swipeProgress.value else 0f)
  nav.popBackStack()
 }

 AppPredictiveBackHandler(
  enabled=allowBackHandler&&!commitInFlight,
  onStart={
   if(!commitInFlight&&nav.currentBackStackEntry?.id==entry.id) {
    gestureInFlight=true
    interactiveGen=coverCoordinator.beginReturnSession(
     key=coverKey,
     route=albumRoute,
     identity=identity,
     physicalMotion=motion,
     sourceNavEntryId=entry.id,
     targetNavEntryId=prevShelfEntryId,
     interactive=true,
     orientation=orientation,
    )
   }
  },
  onProgress={ event ->
   if(!commitInFlight) {
    val p=event.progress.coerceIn(0f,1f)
    scope.launch{swipeProgress.snapTo(p)}
    interactiveGen?.let{coverCoordinator.updateInteractiveReturn(it,p)}
   }
  },
  onCancel={
   gestureInFlight=false
   interactiveGen?.let{coverCoordinator.cancelInteractiveReturn(it)}
   interactiveGen=null
   scope.launch {
    if(motion)swipeProgress.animateTo(0f,tween(CoverMorphCancelMillis,easing=AppNavEasing))
    else swipeProgress.snapTo(0f)
   }
  },
  onBack={commitReturn(fromGesture=gestureInFlight||interactiveGen!=null||swipeProgress.value>0.01f)},
 )

 Box(
  Modifier
   .fillMaxSize()
   .onSizeChanged{routeWidthPx=it.width.toFloat()}
   .graphicsLayer {
    val p=swipeProgress.value.coerceIn(0f,1f)
    if(motion&&p>0f) {
     val morphingReturn=interactiveGen!=null||coverCoordinator.isReturning(albumRoute)
     if(morphingReturn) {
      translationX=routeWidthPx*0.16f*p
      alpha=1f-0.16f*p
     } else {
      translationX=routeWidthPx*p
     }
    }
   },
 ) {
  content{commitReturn(fromGesture=false)}
 }
}

@Composable fun PilgrimageApp(session:SessionViewModel,requireAccount:Boolean=true,allowGuestTrips:Boolean=false) = PilgrimageTheme {
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
   MusicSpaceContent(session,account,requireAccount,allowGuestTrips)
  }
 } else MusicSpaceContent(session,account,requireAccount,allowGuestTrips)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MusicSpaceContent(session:SessionViewModel,account:MusicAccountViewModel,requireAccount:Boolean,allowGuestTrips:Boolean) {
  val owner by session.signedIn.collectAsState();val recovery by session.recovery.collectAsState();val profile by session.profile.collectAsState()
  val openAuth by session.openAuth.collectAsState()
  key(Unit) {
   var authPending by rememberSaveable {mutableStateOf(false)}
   var pendingForm by rememberSaveable {mutableStateOf<String?>(null)}
   val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val route=entry?.destination?.route
   val navigationReady=entry!=null
   val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
   val context=LocalContext.current
   val savedWorldJson=rememberSaveable{mutableStateOf<String?>(null)}
   val wallBrowse=remember(context){
    RoomBrowseState(context).apply {
     savedWorldJson.value?.let { raw ->
      runCatching { WorldCameraSnapshot.parse(JSONObject(raw)) }.getOrNull()?.let(::restoreWorldState)
     }
    }
   }
   LaunchedEffect(wallBrowse){snapshotFlow{wallBrowse.worldState?.json()?.toString()}.collect{savedWorldJson.value=it}}
   DisposableEffect(wallBrowse){onDispose{wallBrowse.worldController?.destroy();wallBrowse.worldController=null}}
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
   var recordDirty by remember{mutableStateOf(false)}
   var recordGuardOwner by remember{mutableStateOf<FormViewModel?>(null)}
   LaunchedEffect(route){if(route!="form/{kind}/{target}"){recordGuardOwner=null;recordLeaveGuard=null;recordSaving=false;recordDirty=false}}
   val coverCoordinator=remember{CoverTransitionCoordinator()}
   val detailZoom=remember{DetailZoomCoordinator()}
   val sheetCoordinator=remember{AppSheetCoordinator()}
   val photoViewerCoordinator=remember{AppPhotoViewerCoordinator()}
   val haptics=rememberAppHaptics()
   val motion=LocalPhysicalMotion.current
   val density=LocalDensity.current
   var settingsTitleCollapsed by remember{mutableStateOf(false)}
   LaunchedEffect(route){
    if(route!="album/{id}"&&coverCoordinator.activeMorph?.mode==CoverTransitionCoordinator.Mode.ENTERING){
     coverCoordinator.cancelAll()
    }
   }
   LaunchedEffect(savedNotice,entry?.id) {
    val notice=savedNotice ?: return@LaunchedEffect
    if(notice.destination==entry?.id)delay(2600)
    if(savedNotice===notice)savedNotice=null
   }
   val music:MusicSpaceViewModel=viewModel()
   LaunchedEffect(music,context) {
    music.favoriteFailures.collect {
     Toast.makeText(context,"收藏没有更新，请再试一次",Toast.LENGTH_SHORT).show()
    }
   }
   val archive:MusicArchiveViewModel=viewModel()
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
   LaunchedEffect(route){
    if(route=="performance"||route=="perform/{song}")performanceLibrary.onWallEntered()
   }
   val previewPlayable=LocalPerformancePreviews.current
   val performanceItems=remember(performanceLibraryUi.items,previewPlayable) {
    performanceLibraryUi.items+previewPlayable.filter{preview->performanceLibraryUi.items.none{it.id==preview.song.id}}.map{it.toCatalogItem()}
   }
   var performanceImmersive by remember{mutableStateOf(false)}
   var playFilterOpen by rememberSaveable{mutableStateOf(false)}
   var playSearchQuery by rememberSaveable{mutableStateOf("")}
   var playSelectedArtist by rememberSaveable{mutableStateOf<String?>(null)}
   val performanceArtworkFor:(String,String,String,List<String>)->ReleaseArtwork?={title,artist,workKey,placeIds->
    val normTitle=musicTerm(title);val normArtist=musicTerm(artist)
    val publicPlaces=sourceCatalog.publicOnly().places
    val byWorkKey=if(workKey.isNotEmpty()) {
     musicCatalog.works.firstOrNull{it.id==workKey}?.artwork
      ?: publicPlaces.asSequence().flatMap{it.scenes.asSequence()}
       .firstOrNull{"${it.artistId}|${musicTerm(it.work)}"==workKey&&it.displayCover?.approved==true}?.displayCover
    } else null
    byWorkKey
     ?: musicCatalog.works.firstOrNull{musicTerm(it.title)==normTitle&&musicTerm(it.artist)==normArtist}?.artwork
     ?: musicCatalog.releases.firstOrNull{musicTerm(it.title)==normTitle&&musicTerm(it.artist)==normArtist}?.artwork
     ?: if(placeIds.isNotEmpty()) {
      val placeSet=placeIds.toSet()
      publicPlaces.asSequence().filter{it.id in placeSet}.flatMap{it.scenes.asSequence()}
       .firstOrNull{musicTerm(it.artistName)==normArtist&&it.displayCover?.approved==true}?.displayCover
     } else null
     ?: musicCatalog.works.firstOrNull{musicTerm(it.title)==normTitle}?.artwork
   }
   fun findPerformanceForWork(work:MusicWork):PerformanceCatalogItem? {
    val normTitle=musicTerm(work.title);val normArtist=musicTerm(work.artist)
    return performanceItems.firstOrNull{it.workKey.isNotEmpty()&&it.workKey==work.id}
     ?: performanceItems.firstOrNull{musicTerm(it.title)==normTitle&&musicTerm(it.artist)==normArtist}
     ?: performanceItems.firstOrNull{musicTerm(it.title)==normTitle}
   }
   val libraryOwner=remember(owner){owner?.let{"user:$it"} ?: LibraryDatabase.guestOwner(context)}
   val tripState=storedTrips.takeIf{it.owner==libraryOwner}?:MusicTripState(libraryOwner,busy=true)
   val tripCities=remember(sourceCatalog,tripState.trips){
    (tripCatalogueCities(sourceCatalog)+tripState.trips.flatMap{it.cities}).distinctBy{it.key}
   }
   var addTripPlace by rememberSaveable(owner){mutableStateOf<String?>(null)}
   var addTripEntry by rememberSaveable(owner){mutableStateOf<String?>(null)}
   fun closeAddTrip() {addTripPlace=null;addTripEntry=null}
   LaunchedEffect(entry?.id) {if(addTripEntry!=null&&addTripEntry!=entry?.id)closeAddTrip()}
   val musicFavorites=storedMusicFavorites.takeIf{it.owner==libraryOwner} ?: MusicFavorites()
   val archiveState=storedArchive.takeIf{it.owner==owner} ?: MusicArchiveState(owner=owner,loading=owner!=null)
   fun findBackStackEntry(path:String)=try { nav.getBackStackEntry(path) }catch(_:IllegalArgumentException){ null }
   fun navigateTo(path:String,placeEntry:Bundle?=null,useThumbnail:Boolean=false) {
    val current=nav.currentBackStackEntry?:return
    if(findBackStackEntry(path)?.id==current.id)return
    val anchor=if(motion&&useThumbnail)detailZoom.capture(current.id,path)else null
    focus.clearFocus();keyboard?.hide();nav.navigate(path)
    nav.currentBackStackEntry?.let { target ->
     if(placeEntry!=null)target.savedStateHandle[PlaceArchiveEntry]=placeEntry
     if(anchor!=null)detailZoom.bind(target,anchor)
    }
   }
   val navigate:(String)->Unit={navigateTo(it)}
   fun followMusicArchiveLink(source:NavBackStackEntry,target:String,origin:Bundle?=null,useThumbnail:Boolean=false) {
    if(nav.currentBackStackEntry!=source)return
    if(!nav.popBackStack(target,inclusive=false))navigateTo(target,origin,useThumbnail)
   }
   fun replaceLegacyRoute(source:NavBackStackEntry,target:String) {
    if(nav.currentBackStackEntry!=source)return
    val existing=findBackStackEntry(target)
    if(existing!=null&&existing.id==nav.previousBackStackEntry?.id)nav.popBackStack()
    else nav.navigate(target){popUpTo(source.destination.id){inclusive=true}}
   }
   fun openArchivePlace(source:NavBackStackEntry,id:String,links:List<MusicPlaceLink>) {
    if(nav.currentBackStackEntry!=source)return
    val link=links.find{it.place.id==id}?:return
    val origin=Bundle().apply {
     putStringArrayList("scenes",ArrayList(link.scenes.map(Scene::id)))
    }
    followMusicArchiveLink(source,"place/${Uri.encode(id)}",origin,useThumbnail=true)
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
   val canUseTrips=owner!=null||allowGuestTrips
   val login:()->Unit={authPending=true;session.clearError();session.notice.value=null;navigate("auth")}
   val openTrips:()->Unit={if(!canUseTrips)login()else navigate("trips")}
   val addToTrip:(Place)->Unit={place->if(!canUseTrips)login()else {
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
   val openProfile:(String)->Unit={if(owner==null)login() else navigate("profile/${Uri.encode(it)}")}
   // Cold-start callbacks can arrive before NavHost installs its graph.
   LaunchedEffect(recovery,navigationReady){if(recovery&&navigationReady){authPending=true;navigate("auth")}}
   LaunchedEffect(openAuth,navigationReady){if(openAuth&&navigationReady){authPending=true;navigate("auth");session.openAuth.value=false}}
   LaunchedEffect(owner,navigationReady,route,recovery) {
    if(navigationReady&&owner!=null&&!recovery&&route=="auth")finishAuth()
   }
   LaunchedEffect(motion) {
    if(!motion)coverCoordinator.cancelAll()
   }
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
    val encodedId=Uri.encode(release.id)
    val targetRoute="album/$encodedId"
    val ratio=release.artwork?.aspectRatio?:1f
    val predicted=coverCoordinator.estimateAlbumHeroRect(ratio)
    coverCoordinator.startEnter("release:${release.id}",targetRoute,predicted,motion,sourceNavEntryId=entry?.id,releaseId=release.id)
    navigate(targetRoute)
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
   val archiveEdgeToEdge=usesEdgeToEdgeArchiveLayout(route)
   val gameFullscreen=route=="perform/{song}"&&performanceImmersive
   DisposableEffect(gameFullscreen,context) {
    val window=(context as? Activity)?.window
    val controller=window?.let{WindowCompat.getInsetsController(it,it.decorView)}
    if(gameFullscreen){controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller?.hide(WindowInsetsCompat.Type.systemBars())}
    onDispose{if(gameFullscreen)controller?.show(WindowInsetsCompat.Type.systemBars())}
   }
   val wallSection=wallSection(route)
   var interactivePopProgress by remember{mutableFloatStateOf(0f)}
   LaunchedEffect(entry?.id) {
    if(interactivePopProgress>0f) {
     delay(SubpageTransitionMillis.toLong()+60L)
     interactivePopProgress=0f
    }
   }

   var transitionMotion by remember{mutableStateOf(AppRouteMotion.Detail)}
   var transitionTopEntryId by remember{mutableStateOf<String?>(null)}
   var transitionUnderEntryId by remember{mutableStateOf<String?>(null)}
   var transitionUseMorph by remember{mutableStateOf(false)}
   var transitionInteractive by remember{mutableStateOf(false)}
   var detailTransition by remember{mutableStateOf<DetailNavigationTransition?>(null)}

   fun NavGraphBuilder.page(
    path:String,
    content:@Composable AnimatedContentScope.(NavBackStackEntry)->Unit,
   ) {
    appDestination(path){destination->
     val detail=detailTransition?.takeIf{motion&&(destination.id==it.top||destination.id==it.under)}
     val moving=transition.currentState!=transition.targetState
     val slide=motion&&transitionMotion==AppRouteMotion.Detail&&!transitionUseMorph&&detail==null
     val isUnderneath=slide&&destination.id==transitionUnderEntryId
     val isTopPane=slide&&destination.id==transitionTopEntryId&&moving
     val scrimAlpha by transition.animateFloat(
      transitionSpec={detailMotionSpec(transitionInteractive,visibilityThreshold=0.002f)},
      label="under-scrim",
     ) { state ->
      if(isUnderneath&&state!=EnterExitState.Visible) DetailScrimAlpha else 0f
     }
     val foreground=destination.id==detail?.top
     val openness=detail?.let {
      transition.animateFloat({it.progressSpec},label="detail-progress") { state ->
       if((state==EnterExitState.Visible)==foreground)1f else 0f
      }
     }
     val detailLayer=if(detail!=null&&openness!=null&&moving) {
      if(foreground)detail.foreground(openness)else detail.background(openness)
     }else Modifier
     CompositionLocalProvider(
      LocalRouteActive provides (entry?.id==destination.id),
      LocalRouteIsCurrent provides {nav.currentBackStackEntry?.id==destination.id},
      LocalRouteEntryId provides destination.id,
     ) {
      Box(Modifier.fillMaxSize().then(detailLayer).drawWithContent {
       drawContent()
       if(isTopPane) {
        val shadowWidth=with(density){16.dp.toPx()}
        drawRect(
         brush=Brush.horizontalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=0.14f)),startX=-shadowWidth,endX=0f),
         topLeft=Offset(-shadowWidth,0f),
         size=Size(shadowWidth,size.height),
        )
       }
       if(scrimAlpha>0.001f) {
        drawRect(Color.Black.copy(alpha=scrimAlpha))
       }
      }) {
       RoomRouteFrame(
        destination,performanceImmersive,!(path=="edit-profile"&&draft.saving)&&!recordSaving,back,
        collapsingTitleVisible=if(path=="settings")settingsTitleCollapsed else true,
        onChooseModule=chooseModule,
        selectedWallTab=if(transitionMotion==AppRouteMotion.Tab)wallSection else null,
       ) {
        content(destination)
       }
       if(detail!=null&&openness!=null&&moving) {
        val decoration=if(foreground)detail.foregroundDecoration(openness)else detail.backgroundDecoration(openness)
        Box(Modifier.matchParentSize().then(decoration))
       }
      }
     }
    }
   }

   CompositionLocalProvider(
    LocalCoverCoordinator provides coverCoordinator,
    LocalDetailZoom provides detailZoom,
    LocalSheetCoordinator provides sheetCoordinator,
    LocalPhotoViewerCoordinator provides photoViewerCoordinator,
    LocalAppHaptics provides haptics,
   ) {
   val orientation=LocalConfiguration.current.orientation
   RoomWall(Modifier.fillMaxSize().onGloballyPositioned {
    val viewport=it.boundsInRoot()
    coverCoordinator.updateViewportBounds(viewport,orientation)
    detailZoom.viewport=viewport
   }) {
    Box(
     Modifier
      .fillMaxSize()
      .graphicsLayer {
       val f=sheetCoordinator.backdropFraction.coerceIn(0f,1.15f)
       val s=1f-0.015f*f
       scaleX=s
       scaleY=s
       transformOrigin=TransformOrigin(0.5f,0.45f)
      }
    ) {
    NavHost(navController=nav,startDestination="room",modifier=Modifier.fillMaxSize(),
     enterTransition={
      val m=routeMotion(initialState.destination.route,targetState.destination.route)
      val albumRoute=if(targetState.destination.route=="album/{id}")"album/${Uri.encode(targetState.arguments?.getString("id").orEmpty())}" else null
      val morphing=albumRoute!=null&&coverCoordinator.isEntering(albumRoute)
      transitionMotion=m;transitionTopEntryId=targetState.id;transitionUnderEntryId=initialState.id;transitionUseMorph=morphing
      transitionInteractive=false
      detailTransition=if(motion&&m==AppRouteMotion.Detail&&!morphing)detailZoom.transition(targetState.id,initialState.id,returning=false,interactive=false)else null
      when {
       !motion||detailTransition!=null -> EnterTransition.None
       morphing -> m.morphEnter()
       else -> m.enter(initialAlpha=if(m==AppRouteMotion.Tab)0f else .55f)
      }
     },
     exitTransition={
      if(!motion)ExitTransition.None else if(transitionUseMorph)AppRouteMotion.Detail.morphExitUnder()
      else if(detailTransition!=null)ExitTransition.KeepUntilTransitionsFinished
      else routeMotion(initialState.destination.route,targetState.destination.route).exit()
     },
     popEnterTransition={
      val m=routeMotion(initialState.destination.route,targetState.destination.route)
      val albumRoute=if(initialState.destination.route=="album/{id}")"album/${Uri.encode(initialState.arguments?.getString("id").orEmpty())}" else null
      val fromAlbumMorph=albumRoute!=null&&coverCoordinator.isReturning(albumRoute)
      val fromAlbumSwipe=albumRoute!=null&&interactivePopProgress>0.01f
      transitionMotion=m;transitionTopEntryId=initialState.id;transitionUnderEntryId=targetState.id;transitionUseMorph=fromAlbumMorph
      transitionInteractive=nav.currentBackStackEntry?.id==initialState.id
      detailTransition=if(motion&&m==AppRouteMotion.Detail&&!fromAlbumMorph&&!fromAlbumSwipe)detailZoom.transition(initialState.id,targetState.id,returning=true,interactive=transitionInteractive)else null
      when {
       !motion||detailTransition!=null -> EnterTransition.None
       fromAlbumMorph -> m.morphPopEnterUnder()
       m==AppRouteMotion.Detail && fromAlbumSwipe -> {
        val remFactor=(1f-interactivePopProgress).coerceIn(0.15f,1f)
        val dur=(SubpageTransitionMillis*remFactor).roundToInt().coerceIn(90,SubpageTransitionMillis)
        slideInHorizontally(tween(dur,easing=AppNavEasing)){-(it*DetailParallaxFraction*remFactor).roundToInt()}
       }
       else -> m.popEnter(transitionInteractive)
      }
     },
     popExitTransition={
      val m=routeMotion(initialState.destination.route,targetState.destination.route)
      val albumRoute=if(initialState.destination.route=="album/{id}")"album/${Uri.encode(initialState.arguments?.getString("id").orEmpty())}" else null
      val fromAlbumMorph=albumRoute!=null&&coverCoordinator.isReturning(albumRoute)
      when {
       !motion -> ExitTransition.None
       detailTransition!=null -> ExitTransition.KeepUntilTransitionsFinished
       fromAlbumMorph -> m.morphPopExit()
       m==AppRouteMotion.Detail && albumRoute!=null && interactivePopProgress>0.01f -> {
        val remFactor=(1f-interactivePopProgress).coerceIn(0.15f,1f)
        val dur=(SubpageTransitionMillis*remFactor).roundToInt().coerceIn(90,SubpageTransitionMillis)
        slideOutHorizontally(tween(dur,easing=AppNavEasing)){(it*remFactor).roundToInt()}
       }
       else -> m.popExit(transitionInteractive)
      }
     }) {
     page("room"){NativeWallTab(0,{navigate("search")}){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}}
     page("catalogue"){NativeWallTab(0,{navigate("search")}){SwissRecordWall(musicCatalog,wallBrowse,openRelease)}}
     page("album/{id}"){e->musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.let {release->
      AlbumArchiveRoute(
       entry=e,
       release=release,
       nav=nav,
       coverCoordinator=coverCoordinator,
       allowBackHandler=entry?.id==e.id&&addTripPlace==null&&sheetCoordinator.entries.isEmpty()&&!photoViewerCoordinator.isOpen&&discardAction==null,
       onStartInteractivePop={interactivePopProgress=it},
      ) { onBack ->
       RoomAlbum(
        release,release.id in musicFavorites.releases,{music.toggleFavorite(release.id,true)},
        {followMusicArchiveLink(e,"song/${Uri.encode(it.id)}")},{openArchivePlace(e,it,release.places)},
        onBack=onBack,onSearch={navigate("release-search/${Uri.encode(release.id)}")},
       )
      }
     }}
     page("search"){RoomSearch(musicCatalog){navigateTo("song/${Uri.encode(it.id)}",useThumbnail=true)}}
     page("release-search/{id}"){e->RoomSearch(musicCatalog,musicCatalog.releases.find{it.id==e.arguments?.getString("id")}?.title.orEmpty()){navigateTo("song/${Uri.encode(it.id)}",useThumbnail=true)}}
     appDestination("music-favorites/{kind}"){e->
      val kind=if(e.arguments?.getString("kind")=="releases")"releases" else "songs"
      LaunchedEffect(e.id,kind){replaceLegacyRoute(e,"archive/$kind")}
     }
     page("song/{id}"){e->musicCatalog.works.find{it.id==e.arguments?.getString("id")}?.let {work->
      LaunchedEffect(work.id,libraryOwner){music.browseWork(work.id)}
      val playableTrack=findPerformanceForWork(work)
      RoomSong(work,musicCatalog.releases.find{it.id==work.releaseId}?.title.orEmpty(),work.id in musicFavorites.songs,{music.toggleFavorite(work.id)},
       {openArchivePlace(e,it,work.places)},
       onBack=back,onSearch={navigate("search")},
       onPerform=playableTrack?.let{track-> {navigate("perform/${Uri.encode(track.id)}")}})
     }}
     page("explore"){e->
      SwissWorldWall(sourceCatalog,wallBrowse,archiveState.places.toSet(),archiveState.loading,
       {navigate("place/${Uri.encode(it)}")},togglePlace,addToTrip,
       {navigate("search")},back,archiveState.visits.map{it.location}.toSet(),active=entry==e)
     }
     page("performance"){
      val hasPlayFilter=playSearchQuery.isNotBlank()||playSelectedArtist!=null
      NativeWallTab(
       section=2,
       onSearch={navigate("search")},
       beforeSearch={
        Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
         IconButton(
          onClick={
           if(playFilterOpen&&hasPlayFilter) {
            playSearchQuery="";playSelectedArtist=null;playFilterOpen=false
           } else {
            playFilterOpen=!playFilterOpen
           }
          },
          modifier=Modifier.size(48.dp).testTag("play-wall-filter-button"),
         ) {
          Icon(
           Icons.Outlined.FilterList,
           contentDescription="筛选曲目",
           modifier=Modifier.size(22.dp),
           tint=if(playFilterOpen||hasPlayFilter)SwissRed else RoomColors.Ink,
          )
         }
         if(hasPlayFilter&&!playFilterOpen) {
          Box(Modifier.align(Alignment.TopEnd).padding(top=11.dp,end=11.dp).size(5.dp).background(SwissRed,CircleShape))
         }
        }
       },
      ){
       SwissPlayWall(
        entries=performanceItems,
        loaded=performanceLibraryUi.loaded,
        browse=wallBrowse,
        downloads=performanceLibraryUi.downloads,
        onSong={navigate("perform/${Uri.encode(it)}")},
        onRetry={
         performanceLibraryUi.downloads.entries.firstOrNull{it.value.failed}?.key?.let{failedId->
          performanceLibrary.prepareSong(failedId,retry=true)
         }
        },
        catalogError=performanceLibraryUi.catalogError,
        onRefreshCatalog=performanceLibrary::retryCatalog,
        filterExpanded=playFilterOpen,
        searchQuery=playSearchQuery,
        onSearchQueryChange={playSearchQuery=it},
        selectedArtist=playSelectedArtist,
        onSelectArtist={playSelectedArtist=it},
       )
      }
     }
     page("perform/{song}"){e->
      val songId=e.arguments?.getString("song").orEmpty()
      val performanceItem=performanceItems.find{it.id==songId}
      LaunchedEffect(songId,performanceItem?.chartReady,performanceItem?.audioReady) {
       if(performanceItem!=null&&(!performanceItem.chartReady||!performanceItem.audioReady)) {
        performanceLibrary.prepareSong(songId)
       }
      }
      if(performanceItem!=null)key(libraryOwner){
        val readyEntry=performanceItem.entry
        if(readyEntry!=null&&readyEntry.audioReady) {
         val song=readyEntry.song
         val work=musicCatalog.works.firstOrNull{
          (performanceItem.workKey.isNotEmpty()&&it.id==performanceItem.workKey) ||
           (musicTerm(it.title)==musicTerm(song.title)&&musicTerm(it.artist)==musicTerm(song.artist))
         }
         val related:(()->Unit)?=work?.takeIf{it.places.isNotEmpty()}?.let{match->{navigate("song/${Uri.encode(match.id)}")}}
           ?: song.placeIds.firstOrNull()?.let{id->{navigate("place/${Uri.encode(id)}")}}
         PerformanceFlow(readyEntry,libraryOwner,back,
           performanceArtworkFor(song.title,song.artist,performanceItem.workKey,song.placeIds.ifEmpty{performanceItem.placeIds}),
           related,{performanceImmersive=it},
           importing=performanceLibraryUi.importing==song.id,importProgress=performanceLibraryUi.progress,
           importMessage=performanceLibraryUi.error ?: performanceLibraryUi.notice,directStart=true)
        } else {
          val status=performanceLibraryUi.downloads[performanceItem.id]
          RoomPerformanceLoading(
            title=performanceItem.title,
            artist=performanceItem.artist,
            artwork=performanceArtworkFor(performanceItem.title,performanceItem.artist,performanceItem.workKey,performanceItem.placeIds),
            statusLabel=status?.label?:"音乐准备中",
            progress=status?.progress,
            failed=status?.failed==true,
            onRetry={performanceLibrary.prepareSong(performanceItem.id,retry=true)},
            onBack=back,
          )
        }
      }
      else if(!performanceLibraryUi.loaded)CircularProgressIndicator(Modifier.padding(24.dp),color=RoomColors.Navy)
      else Text("这首作品正在校对，请返回曲目。",Modifier.padding(24.dp))
     }
     // Resolve saved navigation stacks from versions that still had these screens.
     listOf("library" to "me","archive-more" to "archive/footprints","account-scores" to "me","account-publications" to "submissions","account-badges" to "me").forEach { (old,destination)->
      appDestination(old){e->LaunchedEffect(e.id){replaceLegacyRoute(e,destination)}}
     }
     page("archive/{kind}"){e->key(libraryOwner) {
      val kind=e.arguments?.getString("kind").orEmpty()
      val places=sourceCatalog.publicOnly().places
      val openPlace:(String)->Unit={navigateTo("place/${Uri.encode(it)}",useThumbnail=true)}
      val initialKind=when(kind){"songs","releases","places"->kind;else->"footprints"}
      val initialFootprint=when(kind){"recreations","messages"->kind;else->"visits"}
      RoomArchive(initialKind,music={selection->
       RoomMusicFavorites(musicCatalog,musicFavorites,selection,{navigateTo("song/${Uri.encode(it.id)}",useThumbnail=true)},openRelease,
        {id,release->placeUndo?.let{archive.dismissUndo(it.token)};music.removeFavorite(id,release)},
        {chooseModule("catalogue")})
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
      val origin=e.savedStateHandle.get<Bundle>(PlaceArchiveEntry)
      RoomPlaceDetail(place,origin?.getStringArrayList("scenes").orEmpty().toSet(),
       viewModel(key="place-$owner-${place.id}"),session,place.id in archiveState.places,archiveState.loading,{togglePlace(place.id)},login,openForm,openProfile,
       {addToTrip(place)},musicCatalog,
       onMusicArchive={target->followMusicArchiveLink(e,"${if(target.isRelease)"album" else "song"}/${Uri.encode(target.id)}")},
       onBack=back,onMap={wallBrowse.focusWorld(place.id);navigate("explore")})
     }?:Text("这个地点暂未收录，请返回重试。",Modifier.padding(24.dp))}
     page("trips"){if(!canUseTrips)LaunchedEffect(Unit){login()}else key(libraryOwner){RoomTripList(tripState,libraryOwner,tripCities,trips){navigate("trip/${Uri.encode(it)}")}}}
     page("trip/{id}"){e->if(!canUseTrips)LaunchedEffect(Unit){login()}else key(libraryOwner){tripState.trips.find{it.id==e.arguments?.getString("id")}?.let{RoomTripDetail(it,tripState,libraryOwner,trips,{id->navigate("place/${Uri.encode(id)}")},onBack=back,onDiscover={chooseModule("explore")},catalog=sourceCatalog.publicOnly())}?:Text(if(tripState.busy)"正在载入行程…"else "这个行程暂不可用，请返回重试。",Modifier.padding(24.dp))}}
     page("auth"){if(recovery)AuthScreen(session,{authPending=false;pendingForm=null;back()})else RoomEmailLoginRoute(account,{account.otp.reset();authPending=false;pendingForm=null;back()})}
     page("edit-profile"){e->
      LaunchedEffect(owner){editor.load(true)}
      RoomProfileEditor(draft,editor::nickname,editor::choosePhoto,editor::removePhoto,{
       editor.save {updated->if(updated.optString("id")==owner){session.profile.value=updated;val task=pendingForm;pendingForm=null;nav.popBackStack();if(task!=null)navigate(task)else showSaved("个人资料已更新")}}
      },{editor.load(true)})
      AppPredictiveBackHandler(enabled=entry?.id==e.id&&(draft.dirty||draft.saving),onBack=back)
     }
     page("settings"){RoomSettings(pref,owner!=null,sessionBusy,sessionError,
       {value->scope.launch{runCatching{preferences.setMuted(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},{value->scope.launch{runCatching{preferences.setReducedMotion(value)}.onFailure{session.error.value="无法保存设置，请重试"}}},navigate,
       {session.signOut{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}},onCollapsedTitleChanged={settingsTitleCollapsed=it})}
     listOf("email","privacy","storage","about").forEach{kind->page("account-$kind"){
      RoomAccountInfo(kind,session.services.accountEmail(owner),owner!=null,sessionBusy,sessionError,
       {session.deleteAccount{if(!requireAccount)nav.navigate("auth"){popUpTo(nav.graph.id){inclusive=true}}}},login)
     }}
     appDestination("mine/{kind}"){e->
      val kind=e.arguments?.getString("kind")
      LaunchedEffect(e.id,kind) {
       val destination=when(kind){"visit"->"archive/footprints";"recreation"->"archive/recreations";"message"->"archive/messages";else->"me"}
       replaceLegacyRoute(e,destination)
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
       {saving->if(recordGuardOwner===vm)recordSaving=saving},
       {dirty->if(recordGuardOwner===vm)recordDirty=dirty})
      else if(kind in listOf("new_location","correction"))RoomSubmissionForm(kind,target,editing,vm,back)
      else LaunchedEffect(Unit){nav.navigate("me"){popUpTo("room");launchSingleTop=true}}
      // Only intercept predictive back when edits exist or saving is in flight.
      AppPredictiveBackHandler(enabled=entry?.id==e.id&&kind in listOf("message","visit","recreation")&&(recordSaving||recordDirty),onBack=back)
     }
     page("camera/{scene}"){e->
      val catalog by session.services.catalog.collectAsState();val scene=catalog.places.flatMap{it.scenes}.find{it.id==e.arguments?.getString("scene")}
      CameraScreen(scene,back){uri->nav.previousBackStackEntry?.savedStateHandle?.set("camera_photo",uri.toString());nav.popBackStack()}
     }
    }
    CoverTransitionOverlay(coverCoordinator)
    Box(Modifier.matchParentSize().then(if(gameFullscreen||archiveEdgeToEdge)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
   if(archiveRoute) {
    musicUndo?.let{undo->RoomUndoNotice(undo.label,undo.token,{music.undoRemoval(undo.token)},{music.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
    placeUndo?.takeIf{it.owner==owner}?.let{undo->RoomUndoNotice("已取消地点收藏",undo.token,{archive.undoRemoval(undo.token)},{archive.dismissUndo(undo.token)},Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth())}
   }
   if(route in listOf("explore","place/{id}")&&archiveState.error!=null)Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),color=RoomColors.Paper,shape=RoundedCornerShape(6.dp),shadowElevation=4.dp){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(archiveState.error.orEmpty(),Modifier.weight(1f),color=RoomColors.Ink);TextButton(archive::reload){Text("重试")}}}
   }
    }
   val notice=savedNotice?.takeIf{it.destination==entry?.id}
   AppSavedConfirmNotice(
    message=notice?.message,
    visible=notice!=null,
    modifier=Modifier.align(Alignment.BottomCenter)
     .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
     .padding(bottom=if(wallSection!=null)SwissWallTabsHeight+12.dp else 20.dp),
   )
   addTripPlace?.takeIf{addTripEntry==entry?.id}?.let { id ->
    sourceCatalog.publicOnly().places.find{it.id==id}
   }?.let{place->
    RoomAddToTripDialog(place,tripState,libraryOwner,tripCities,trips,onAdded={
     val onSource=nav.currentBackStackEntry?.id==addTripEntry
     closeAddTrip()
     if(onSource)showSaved("已加入行程")
    },onClose=::closeAddTrip)
   }
   sheetCoordinator.entries.forEach { portalEntry ->
    key(portalEntry.id) { portalEntry.content() }
   }
   AppPhotoViewerPortal(photoViewerCoordinator)
   if(discardAction!=null)RoomDiscardProfileDialog({discardAction=null},{val action=discardAction;discardAction=null;editor.discard();action?.invoke()})
   performanceLibraryUi.mobileConfirmationSongId?.let { pendingId ->
    val pending=performanceItems.find{it.id==pendingId}
    val mbText=pending?.opusBytes?.takeIf{it>0L}?.let{"约 %.1f MB".format(it/1048576.0)}?:"约 1.3 MB"
    AppConfirmation(
     title="用移动网络准备音乐？",
     message="将下载《${pending?.title ?: "当前曲目"}》（$mbText），完成后可离线演奏。",
     confirmLabel="开始准备",onConfirm=performanceLibrary::allowMobile,
     cancelLabel="等待 Wi-Fi",onDismiss=performanceLibrary::waitForWifi,
    )
   }
   }
  }
 }
}

