import { MovieWord } from './FlashcardModels';
export interface QuizWordPage {
  words: MovieWord[];
  total: number;
}
export interface QuestionOption {
  text: string;
  word?: string;
}
export interface QuizQuestion {
  id: string;
  word: string;
  options: QuestionOption[];
}
export interface QuizAnswer {
  selected_index: number;
  correct_index: number;
  correct: boolean;
  options: QuestionOption[];
}
