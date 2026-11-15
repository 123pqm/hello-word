import { ApiError, HttpClient } from '../common/HttpClient';

export class LoginResponse {
  user_id: number = 0;
  account: string = '';
  reply: string = '';
  access_token: string = '';
  token_type: string = '';
  expires_in: number = 0;
}

export class SignResponse {
  id: number = 0;
  reply: string = '';
}

export class AuthService {
  static sign(account: string, password: string): Promise<SignResponse> {
    return HttpClient.postJson('/user/sign', JSON.stringify({ account: account, password: password }))
      .then((raw: string) => {
        return JSON.parse(raw) as SignResponse;
      });
  }

  static login(account: string, password: string): Promise<LoginResponse> {
    return HttpClient.postJson('/user/login', JSON.stringify({ account: account, password: password }))
      .then((raw: string) => {
        const response: LoginResponse = JSON.parse(raw) as LoginResponse;
        if (!response || typeof response.access_token !== 'string' || response.access_token.length === 0) {
          throw new ApiError(200, '登录响应缺少有效 access_token，请检查后端接口版本');
        }
        if (typeof response.user_id !== 'number' || !Number.isInteger(response.user_id) || response.user_id <= 0) {
          throw new ApiError(200, '登录响应缺少有效 user_id，可能启动了旧版后端。请运行 D:/hello-word-repo/dev.ps1');
        }
        if (typeof response.account !== 'string' || response.account.length === 0) {
          throw new ApiError(200, '登录响应缺少有效 account，请检查后端接口版本');
        }
        return response;
      });
  }

  static logout(token: string): Promise<void> {
    return HttpClient.postJson('/user/logout', '{}', token).then(() => {});
  }
}
