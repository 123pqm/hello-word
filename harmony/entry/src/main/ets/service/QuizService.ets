import { HttpClient } from '../common/HttpClient';
import { QuizQuestion, QuizAnswer, QuizWordPage } from '../models/QuizModels';

export class QuizService {
  static async words(movieId: number, offset: number, token: string): Promise<QuizWordPage> {
    const result = JSON.parse(await HttpClient.getJson(`/video/${movieId}/quiz-words?offset=${offset}&limit=5`, token)) as QuizWordPage;
    if (!Array.isArray(result.words) || result.words.length > 5 || !Number.isInteger(result.total) || result.total < 0 ||
      (result.words.length === 0 && offset < result.total)) throw new Error('单词分页数据异常，请重试');
    return result;
  }

  static async question(movieId: number, wordId: number, token: string): Promise<QuizQuestion> {
    const result = JSON.parse(await HttpClient.postJson(`/video/${movieId}/words/${wordId}/question`, '', token)) as QuizQuestion;
    if (!result.id || !Array.isArray(result.options) || result.options.length !== 4) throw new Error('题目数据异常');
    return result;
  }
  static async answer(id: string, index: number, token: string): Promise<QuizAnswer> {
    const result = JSON.parse(await HttpClient.postJson(`/video/questions/${id}/answer`,
      JSON.stringify({ selected_index: index }), token)) as QuizAnswer;
    if (!Array.isArray(result.options) || result.options.length !== 4 ||
      !Number.isInteger(result.correct_index) || result.correct_index < 0 || result.correct_index > 3 ||
      !Number.isInteger(result.selected_index) || result.selected_index < 0 || result.selected_index > 3) {
      throw new Error('答题结果异常');
    }
    return result;
  }
}
