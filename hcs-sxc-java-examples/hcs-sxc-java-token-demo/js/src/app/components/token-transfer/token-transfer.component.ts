import {Component} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {AlertService, TokenService, AuthenticationService, AppService, UsersService} from '@/services';
import {Token, User} from '@/models';

@Component({
  templateUrl: './token-transfer.component.html'
})
export class TokenTransferComponent {

  currentToken: Token;
  currentUser: User = new User();
  whoTo: string;
  quantity: number;
  allUsers: User[];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private tokenService: TokenService,
    private authenticationService: AuthenticationService,
    private usersService: UsersService,
    private alertService: AlertService) {

    this.authenticationService.currentUser.subscribe(x => this.currentUser = x);
    this.tokenService.currentTokenObservable.subscribe(
      x => this.currentToken = x
    );
    this.usersService.allUsers.subscribe(
      x => this.allUsers = x
    );
    // redirect to home if not logged in
    if (! this.authenticationService.currentUserValue) {
      this.router.navigate(['/']);
    }
  }

  onSubmit() {
    this.tokenService.transfer(this.currentUser, this.quantity, this.whoTo)
      .subscribe(
        (data) => {
          this.alertService.success(data.result, true);
        },
        (error) => {
          this.alertService.error(error);
        }
      );
  }
}
