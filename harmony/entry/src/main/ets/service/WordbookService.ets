import { HttpClient, ApiError } from '../common/HttpClient';
import { WordSuggestionResponse } from '../models/WordbookModels';
import { WordbookWordListResponse, WordbookWord, WordSearchResponse, FavoritePage, FavoritesResponse, FavoriteWord } from '../models/WordbookModels';

export class WordbookService {
  static async suggestWords(bookId: number, keyword: string, token: string): Promise<WordSuggestionResponse> {
    const path = `/wordbooks/${bookId}/suggestions?q=${encodeURIComponent(keyword)}`;
    const result = JSON.parse(await HttpClient.getJson(path, token)) as WordSuggestionResponse;
    if (!result || result.book_id !== bookId || !Array.isArray(result.data) ||
      result.data.some((item: WordbookWord) => typeof item.word !== 'string' || typeof item.meaning !== 'string')) {
      throw new ApiError(200, '候选单词格式异常');
    }
    return result;
  }
  static async getWords(bookId: number, startIndex: number, endIndex: number, token: string): Promise<WordbookWordListResponse> {
    const path = `/wordbooks/${bookId}/words?start_index=${startIndex}&end_index=${endIndex}`;
    const raw = await HttpClient.getJson(path, token);
    const result = JSON.parse(raw) as WordbookWordListResponse;
    if (!result || !Array.isArray(result.data) || typeof result.total !== 'number') {
      throw new ApiError(200, '词条列表格式异常，请确认后端版本');
    }
    for (let i = 0; i < result.data.length; i++) {
      const w: WordbookWord = result.data[i];
      if (typeof w.word !== 'string' || typeof w.meaning !== 'string') {
        throw new ApiError(200, '词条字段不完整');
      }
    }
    return result;
  }

  static async searchWords(bookId: number, keyword: string, offset: number, limit: number, token: string): Promise<WordSearchResponse> {
    const path = `/wordbooks/${bookId}/search?q=${encodeURIComponent(keyword)}&offset=${offset}&limit=${limit}`;
    const result = JSON.parse(await HttpClient.getJson(path, token)) as WordSearchResponse;
    if (!result || !Array.isArray(result.data) || result.book_id !== bookId ||
      typeof result.has_more !== 'boolean' || (result.has_more &&
      (typeof result.next_offset !== 'number' || result.next_offset <= offset))) {
      throw new ApiError(200, '搜索结果格式异常');
    }
    return result;
  }

  static async getFavorites(limit: number, beforeId: number | null, token: string): Promise<FavoritePage> {
    let path = `/favorites/words?limit=${limit}`;
    if (beforeId !== null) {
      path += `&before_id=${beforeId}`;
    }
    const raw = await HttpClient.getJson(path, token);
    const result = JSON.parse(raw) as FavoritesResponse;
    if (!result || !result.data || !Array.isArray(result.data.items)) {
      throw new ApiError(200, '收藏列表格式异常，请确认后端版本');
    }
    for (let i = 0; i < result.data.items.length; i++) {
      const w: FavoriteWord = result.data.items[i];
      if (typeof w.word !== 'string') {
        throw new ApiError(200, '收藏词条字段不完整');
      }
    }
    return result.data;
  }
}
