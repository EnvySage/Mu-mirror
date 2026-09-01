"""RAG 召回率评测 - 配置文件"""

# Java API 地址
API_BASE = "http://localhost:9005/api"

# 测试账号
USERNAME = "xxx"
PASSWORD = "111111"

# PostgreSQL 连接信息（直连数据库做检索）
DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "database": "mu_mirror",
    "user": "postgres",
    "password": "postgres",
}

# Python AI 项目路径（用于导入 gRPC 生成的代码）
AI_PROJECT_PATH = r"C:\Users\cheng\Desktop\claude\own\Mu-mirror-AI"

# Python Embedding 服务（gRPC）
EMBEDDING_GRPC_HOST = "localhost"
EMBEDDING_GRPC_PORT = 50051

# Embedding 配置（和你 Java 端 user_settings 里的配置保持一致）
EMBEDDING_SOURCE = "api"       # "local" 或 "api"
EMBEDDING_LOCAL_MODEL = "BAAI/bge-m3"
EMBEDDING_API_PROVIDER = "qwen"      # openai / zhipu / qwen
EMBEDDING_API_KEY = "sk-49393920f20842e7ab16c3565ff7c48a"
EMBEDDING_API_MODEL = "qwen3.7-text-embedding"
EMBEDDING_BASE_URL = "https://ws-mfq7lft21wb5seq0.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"

# 创建记录后的等待配置
POLL_INTERVAL = 2      # 轮询间隔（秒）
POLL_TIMEOUT = 120     # 单条记录最大等待时间（秒）

# 检索配置
TOP_K = 10  # 每次检索返回的 top-K 条数
