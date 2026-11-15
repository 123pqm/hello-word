import pytest
from app.services import ai_service

@pytest.fixture(autouse=True)
def isolate(monkeypatch):
    monkeypatch.setattr(ai_service, 'load_dotenv', lambda *a, **k: None)
    monkeypatch.setenv('AI_PROVIDER', 'huaweicloud')
    monkeypatch.setenv('HUAWEI_MAAS_API_KEY', 'fake-maas-key')
    monkeypatch.setenv('HUAWEI_MAAS_MODEL', 'console-model-id')
    monkeypatch.setenv('HUAWEI_MAAS_BASE_URL', 'https://api.modelarts-maas.com/openai/v1/')

def test_huawei_configuration():
    assert ai_service.get_ai_config()==('fake-maas-key','console-model-id','https://api.modelarts-maas.com/openai/v1')

@pytest.mark.parametrize('field,value',[
    ('HUAWEI_MAAS_API_KEY',''), ('HUAWEI_MAAS_MODEL',''),
    ('HUAWEI_MAAS_BASE_URL','http://example.com/v1'),
    ('HUAWEI_MAAS_BASE_URL','https://example.com/v1/chat/completions'),
    ('AI_PROVIDER','unknown'),
])
def test_invalid_config(monkeypatch,field,value):
    monkeypatch.setenv(field,value)
    with pytest.raises(ai_service.AIServiceError) as error:
        ai_service.get_ai_config()
    assert error.value.status_code==503
    assert 'fake-maas-key' not in error.value.detail
