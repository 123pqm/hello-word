import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { AppConfig } from './AppConfig';

export class ApiError extends Error {
  status: number = 0;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export class HttpClient {
  static getJson(path: string, token: string): Promise<string> {
    const headers: Record<string, string> = { 'Accept': 'application/json' };
    if (token.length > 0) headers['Authorization'] = `Bearer ${token}`;
    return HttpClient.request(path, http.RequestMethod.GET, '', headers);
  }
  static request(path: string, method: http.RequestMethod, body: string, headers: Record<string, string>): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      const httpRequest: http.HttpRequest = http.createHttp();
      const options: http.HttpRequestOptions = {
        method: method,
        header: headers,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: AppConfig.REQUEST_TIMEOUT,
        readTimeout: AppConfig.REQUEST_TIMEOUT,
        usingCache: false
      };
      if (body.length > 0) {
        options.extraData = body;
      }
      httpRequest.request(
        AppConfig.BASE_URL + path,
        options,
        (err: BusinessError, data: http.HttpResponse) => {
          httpRequest.destroy();
          if (err) {
            reject(new ApiError(0, `网络错误：${err.message}`));
            return;
          }
          const raw: string = typeof data.result === 'string' ? data.result as string : JSON.stringify(data.result);
          const status: number = data.responseCode;
          if (status >= 200 && status < 300) {
            resolve(raw);
            return;
          }
          let message: string = `请求失败(${status})`;
          try {
            const obj: Record<string, Object> = JSON.parse(raw) as Record<string, Object>;
            if (typeof obj['detail'] === 'string') {
              message = obj['detail'] as string;
            } else if (typeof obj['message'] === 'string') {
              message = obj['message'] as string;
            }
          } catch (e) {
            message = `请求失败(${status})`;
          }
          reject(new ApiError(status, message));
        }
      );
    });
  }

  static postJson(path: string, body: string, token: string = ''): Promise<string> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token.length > 0) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    return HttpClient.request(path, http.RequestMethod.POST, body, headers);
  }
}
