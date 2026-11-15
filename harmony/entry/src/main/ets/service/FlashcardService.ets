import { HttpClient, ApiError } from '../common/HttpClient';
import { AppConfig } from '../common/AppConfig';
import { MovieWord, MovieWordsResponse, PlaybackResponse } from '../models/FlashcardModels';
import { MovieWordCorrection } from '../models/FlashcardModels';

export class FlashcardService {
  static async correct(movieId: number, wordId: number, correction: MovieWordCorrection, token: string): Promise<MovieWord> {
    // 兼容 API 24 设备：PATCH 及独立 body 参数从 API 26 才开始支持。
    const raw = await HttpClient.postJson(`/video/${movieId}/words/${wordId}/correction`,
      JSON.stringify(correction), token);
    const result = JSON.parse(raw) as MovieWord;
    if (result.id !== wordId || !Number.isFinite(result.start_time) || !Number.isFinite(result.end_time) ||
      result.start_time < 0 || result.end_time <= result.start_time || typeof result.sentence_text !== 'string') {
      throw new Error('校正结果格式异常，请重新加载闪卡');
    }
    return result;
  }
  static async words(movieId: number, token: string): Promise<MovieWord[]> {
    if (!Number.isInteger(movieId) || movieId <= 0) throw new Error('电影编号无效');
    const result = JSON.parse(await HttpClient.getJson(`/video/${movieId}/words`, token)) as MovieWordsResponse;
    if (!result || !result.data || result.data.movie_id !== movieId || !Array.isArray(result.data.words)) {
      throw new ApiError(200, '闪卡响应格式异常，请重试');
    }
    for (const item of result.data.words) {
      if (!item || typeof item.word !== 'string' || !item.word.trim() || typeof item.meaning !== 'string' ||
        !Number.isFinite(item.start_time) || !Number.isFinite(item.end_time) || item.start_time < 0 ||
        item.end_time < item.start_time ||
        (item.sentence_text !== undefined && item.sentence_text !== null && typeof item.sentence_text !== 'string')) {
        throw new ApiError(200, '闪卡词条格式异常，请重试');
      }
    }
    // 同一个词可能出现在不同片段，保留后端顺序，不按词文本去重。
    return result.data.words;
  }

  static async playback(movieId: number, token: string): Promise<string> {
    const result = JSON.parse(await HttpClient.postJson(`/video/${movieId}/playback?compatible=true`, '', token)) as PlaybackResponse;
    if (result?.data?.status === 'preparing') throw new ApiError(202, '正在生成兼容视频，首次准备需要一些时间…');
    if (!result || !result.data || typeof result.data.ticket !== 'string' || !result.data.ticket ||
      typeof result.data.expires_at !== 'number' || !Number.isFinite(result.data.expires_at) || result.data.expires_at * 1000 <= Date.now()) {
      throw new ApiError(200, '视频播放凭证无效，请重新连接');
    }
    return `${AppConfig.BASE_URL}/video/${movieId}/stream?ticket=${encodeURIComponent(result.data.ticket)}`;
  }
}
