import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { LandingComponent } from './landing/landing.component';
import { AuthGuard } from './_helpers';
import { ProgrammingComponent } from './programming/programming.component';
import { GamingComponent } from './gaming/gaming.component';
import { RoomComponent } from './programming/room/room.component';
import { ArtshowComponent } from './artshow/artshow.component';
import { ArtistComponent } from './artshow/artist/artist.component';
import { DealersComponent } from './dealers/dealers.component';
import { DealerComponent } from './dealers/dealer/dealer.component';
import { VideoComponent } from './gaming/video/video.component';
import { TabletopComponent } from './gaming/tabletop/tabletop.component';
import { LarpComponent } from './gaming/larp/larp.component';
import { SpecialComponent } from './gaming/special/special.component';

const accountModule = () => import('./account/account.module').then(x => x.AccountModule);
const scheduleModule = () => import('./schedule/schedule.module').then(x => x.ScheduleModule);
const mapModule = () => import('./map/map.module').then(x => x.MapModule);

const routes: Routes = [
  {path: '', component: LandingComponent, canActivate: [AuthGuard]},
  {path: 'account', loadChildren: accountModule},
  {path: 'schedule', loadChildren: scheduleModule, canActivate: [AuthGuard]},
  {path: 'map', loadChildren: mapModule, canActivate: [AuthGuard]},
  {path: 'programming', component: ProgrammingComponent, canActivate: [AuthGuard]},
  {path: 'programming/:id', component: RoomComponent, canActivate: [AuthGuard]},
  {path: 'gaming', component: GamingComponent, canActivate: [AuthGuard]},
  {path: 'gaming/video', component: VideoComponent, canActivate: [AuthGuard]},
  {path: 'gaming/tabletop', component: TabletopComponent, canActivate: [AuthGuard]},
  {path: 'gaming/larp', component: LarpComponent, canActivate: [AuthGuard]},
  {path: 'gaming/special', component: SpecialComponent, canActivate: [AuthGuard]},
  {path: 'artshow', component: ArtshowComponent},
  {path: 'artshow/:id', component: ArtistComponent},
  {path: 'dealers', component: DealersComponent},
  {path: 'dealers/:id', component: DealerComponent},

  //redirect home
  {path: '**', redirectTo: '/map'}
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
