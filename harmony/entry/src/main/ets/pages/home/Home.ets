import { BottomTabBar } from '../../components/common/BottomTabBar';
import { HomeView } from '../../components/common/HomeView';
import { WordBookView } from '../../components/common/WordBookView';
import { AIAssistantView } from '../../components/common/AIAssistantView';
import { MovieView } from '../../components/common/MovieView';
import { ProfileView } from '../../components/common/ProfileView';

@Entry
@Component
struct Home {
  @State account: string = AppStorage.get<string>('account') ?? '';
  @State currentTab: number = 0;
  @State pageVisible: boolean = true;

  onPageShow(): void { this.pageVisible = true; }
  onPageHide(): void { this.pageVisible = false; }

  private readonly PrimaryColor: string = '#5B6BF0';
  private readonly TitleColor: string = '#2C2C34';
  private readonly SubTextColor: string = '#999999';

  build() {
    Column() {
      Column() {
        if (this.currentTab === 0) {
          HomeView({ active: this.pageVisible, onNavigateToMovie: (): void => { this.currentTab = 3; }, onNavigateToAI: (): void => { this.currentTab = 2; } })
        } else if (this.currentTab === 1) {
          WordBookView()
        } else if (this.currentTab === 2) {
          AIAssistantView()
        } else if (this.currentTab === 3) {
          MovieView({ active: this.pageVisible, onBack: (): void => { this.currentTab = 0; } })
        } else if (this.currentTab === 4) {
          ProfileView()
        }
      }
      .layoutWeight(1)
      .width('100%')

      BottomTabBar({
        currentTab: this.currentTab,
        onTabChange: (index: number): void => {
          this.currentTab = index;
        }
      })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F0F2F5')
  }
}