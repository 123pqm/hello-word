import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

export interface FlashcardSettings {
  showMeaning: boolean;
  showExample: boolean;
  lemmatization: boolean;
  modelSelection: string;
  quizMode: boolean;
  autoAddWrongToReview: boolean;
}

export class FlashcardSettingsStore {
  private static readonly PREF_NAME: string = 'flashcard_settings';
  private static readonly KEY_SHOW_MEANING: string = 'show_meaning';
  private static readonly KEY_SHOW_EXAMPLE: string = 'show_example';
  private static readonly KEY_LEMMATIZATION: string = 'lemmatization';
  private static readonly KEY_MODEL_SELECTION: string = 'model_selection';
  private static readonly KEY_QUIZ_MODE: string = 'quiz_mode';
  private static readonly KEY_AUTO_WRONG_REVIEW: string = 'auto_wrong_review';

  static async load(context: common.Context): Promise<FlashcardSettings> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    const showMeaning: boolean = (await pref.get(FlashcardSettingsStore.KEY_SHOW_MEANING, false)) as boolean;
    const showExample: boolean = (await pref.get(FlashcardSettingsStore.KEY_SHOW_EXAMPLE, true)) as boolean;
    const lemmatization: boolean = (await pref.get(FlashcardSettingsStore.KEY_LEMMATIZATION, true)) as boolean;
    const modelSelection: string = (await pref.get(FlashcardSettingsStore.KEY_MODEL_SELECTION, 'tiny')) as string;
    const quizMode: boolean = (await pref.get(FlashcardSettingsStore.KEY_QUIZ_MODE, false)) as boolean;
    const autoAddWrongToReview: boolean = (await pref.get(FlashcardSettingsStore.KEY_AUTO_WRONG_REVIEW, false)) as boolean;
    return { showMeaning, showExample, lemmatization, modelSelection, quizMode, autoAddWrongToReview };
  }

  static async saveShowMeaning(context: common.Context, value: boolean): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_SHOW_MEANING, value);
    await pref.flush();
  }

  static async saveShowExample(context: common.Context, value: boolean): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_SHOW_EXAMPLE, value);
    await pref.flush();
  }

  static async saveLemmatization(context: common.Context, value: boolean): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_LEMMATIZATION, value);
    await pref.flush();
  }

  static async saveModelSelection(context: common.Context, value: string): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_MODEL_SELECTION, value);
    await pref.flush();
  }

  static async saveQuizMode(context: common.Context, value: boolean): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_QUIZ_MODE, value);
    await pref.flush();
  }

  static async saveAutoAddWrongToReview(context: common.Context, value: boolean): Promise<void> {
    const pref: preferences.Preferences = await preferences.getPreferences(context, FlashcardSettingsStore.PREF_NAME);
    await pref.put(FlashcardSettingsStore.KEY_AUTO_WRONG_REVIEW, value);
    await pref.flush();
  }
}