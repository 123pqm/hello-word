import { HttpClient } from '../common/HttpClient';
import { MovieWord } from '../models/FlashcardModels';
import { QuizQuestion, QuizAnswer } from '../models/QuizModels';

export interface ReviewWord extends MovieWord {
  id: number;
  wordbook_id: number;
  review_count: number;
  next_review_at: string;
}
export interface ReviewPage {
  words: ReviewWord[];
  total: number;
  due: number;
}
export class ReviewService {
  static async list(token: string, afterId: number = 0, dueOnly: boolean = false, limit: number = 20): Promise<ReviewPage> {
    return JSON.parse(await HttpClient.getJson(`/reviews?after_id=${afterId}&limit=${limit}&due_only=${dueOnly}`, token)) as ReviewPage;
  }
  static async add(questionId: string, token: string): Promise<void> {
    await HttpClient.postJson(`/reviews/wrong/${questionId}`, '', token);
  }
  static async question(id: number, token: string): Promise<QuizQuestion> {
    return JSON.parse(await HttpClient.postJson(`/reviews/${id}/question`, '', token)) as QuizQuestion;
  }
  static async answer(id: string, index: number, token: string): Promise<QuizAnswer> {
    return JSON.parse(await HttpClient.postJson(`/reviews/questions/${id}/answer`, JSON.stringify({ selected_index: index }), token)) as QuizAnswer;
  }
}
