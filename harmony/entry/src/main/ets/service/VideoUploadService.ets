import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { AppConfig } from '../common/AppConfig';
import { ApiError } from '../common/HttpClient';

export interface VideoUploadResult {
  movie_id: number;
  tracking_token: string;
  status: string;
}
interface UploadResponse { data: VideoUploadResult; }
interface UploadError { detail?: string; message?: string; }

export class VideoUploadService {
  static async upload(uri: string, name: string, cacheDir: string, bookId: number,
    token: string, enableLemmatization: boolean = false, modelSelection: string = 'tiny'): Promise<VideoUploadResult> {
    if (modelSelection !== 'tiny' && modelSelection !== 'base') throw new Error('请选择 tiny 或 base 模型');
    const suffix = name.substring(name.lastIndexOf('.')).toLowerCase();
    const types: Record<string, string> = {
      '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.mkv': 'video/x-matroska', '.avi': 'video/x-msvideo'
    };
    if (!types[suffix]) throw new Error('请选择 MP4、MOV、MKV 或 AVI 视频');
    const path = `${cacheDir}/movie-upload-${Date.now()}${suffix}`;
    const request = http.createHttp();
    try {
      // 通过选择器授予的 URI 打开，再复制到应用缓存；避免把整部视频读进内存。
      const source = await fs.open(uri, fs.OpenMode.READ_ONLY);
      try { await fs.copyFile(source.fd, path); } finally { await fs.close(source); }
      const response = await request.request(`${AppConfig.BASE_URL}/video/upload`, {
        method: http.RequestMethod.POST,
        header: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'multipart/form-data' },
        multiFormDataList: [
          { name: 'file', contentType: types[suffix], remoteFileName: name, filePath: path },
          { name: 'book_id', contentType: 'text/plain', data: `${bookId}` },
          { name: 'enable_lemmatization', contentType: 'text/plain', data: enableLemmatization ? 'true' : 'false' },
          { name: 'whisper_model', contentType: 'text/plain', data: `${modelSelection}.en` },
          { name: 'selection_mode', contentType: 'text/plain', data: 'all_book' }
        ],
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: AppConfig.REQUEST_TIMEOUT,
        readTimeout: 600000,
        usingCache: false
      });
      const raw = response.result as string;
      if (response.responseCode < 200 || response.responseCode >= 300) {
        let message = `上传失败(${response.responseCode})`;
        try {
          const error = JSON.parse(raw) as UploadError;
          if (typeof error.detail === 'string') message = error.detail;
          else if (typeof error.message === 'string') message = error.message;
        } catch {}
        throw new ApiError(response.responseCode, message);
      }
      const result = JSON.parse(raw) as UploadResponse;
      if (!result.data || !Number.isInteger(result.data.movie_id) || result.data.movie_id <= 0 ||
        typeof result.data.tracking_token !== 'string') {
        throw new Error('上传响应异常，请先到电影列表确认，避免重复上传');
      }
      return result.data;
    } finally {
      request.destroy();
      try { await fs.unlink(path); } catch {}
    }
  }
}
