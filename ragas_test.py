import json
import requests
import pandas as pd
from typing import List, Dict, Any
from ragas import evaluate
from ragas.metrics import (
    faithfulness,
    answer_relevancy,
    context_precision,
    context_recall,
    answer_correctness
)
from datasets import Dataset

# ==================== 配置 ====================
# RAG服务配置
RAG_API_BASE = "http://localhost:8081"
SEARCH_API = f"{RAG_API_BASE}/api/v1/search/hybrid"

# 认证token
AUTH_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJwcmltYXJ5T3JnIjoiZGVmYXVsdCIsIm9yZ1RhZ3MiOiJkZWZhdWx0LGFkbWluIiwicm9sZSI6IkFETUlOIiwidG9rZW5JZCI6ImIxOTRjYWU4ZjVlOTRiNTM4MTA0N2M4MTUzNTg1NjQ5IiwidXNlcklkIjoiMSIsInN1YiI6ImFkbWluIiwiZXhwIjoxNzc4NTg5NDU3fQ.B44jWQ-3ObGom_RhUi9R1aBtt2cWwEus5V7q2qIyULY"

# 智谱API配置 (用于生成回答)
DEEPSEEK_API_KEY = "e50c60f01acd4b20b9644d034f51042d.Wmypl3ZYzUEV0OnB"
DEEPSEEK_API_URL = "https://open.bigmodel.cn/api/paas/v4"
DEEPSEEK_MODEL = "glm-4-flash"

# 测试数据路径
TEST_DATA_PATH = r"D:\小游戏\面渣逆袭+进阶之路\ragas_100.json"

# 检索topK
TOP_K = 5

# ==================== 配置 ====================


def load_test_data(path: str) -> List[Dict]:
    """加载测试数据"""
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def search_knowledge(query: str, top_k: int = TOP_K) -> List[str]:
    """调用搜索API获取检索结果"""
    headers = {
        "Authorization": f"Bearer {AUTH_TOKEN}",
        "Content-Type": "application/json"
    }
    params = {
        "query": query,
        "topK": top_k
    }
    
    try:
        response = requests.get(SEARCH_API, headers=headers, params=params, timeout=30)
        response.raise_for_status()
        data = response.json()
        
        if data.get("code") == 200:
            results = data.get("data", [])
            return [r.get("textContent", "") for r in results]
        else:
            print(f"搜索失败: {data.get('message')}")
            return []
    except Exception as e:
        print(f"搜索请求失败: {e}")
        return []


def generate_answer(query: str, context: List[str]) -> str:
    """调用LLM生成回答"""
    if not context:
        return "无检索结果"
    
    context_text = "\n\n".join([f"[来源{i+1}]\n{ctx}" for i, ctx in enumerate(context)])
    
    prompt = f"""你是一个专业的知识库问答助手。请根据以下检索到的信息回答用户问题。

检索信息：
{context_text}

用户问题：{query}

要求：
1. 仅根据检索信息回答，不要编造信息
2. 如果检索信息不足以回答，请说明"根据检索到的信息，无法回答该问题"
3. 回答要简洁准确

回答："""
    
    headers = {
        "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
        "Content-Type": "application/json"
    }
    
    # 智谱API格式
    payload = {
        "model": DEEPSEEK_MODEL,
        "messages": [
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 1000
    }
    
    # 智谱需要完整的chat/completions路径
    api_url = DEEPSEEK_API_URL.rstrip('/') + "/chat/completions"
    
    try:
        response = requests.post(api_url, headers=headers, json=payload, timeout=60)
        response.raise_for_status()
        data = response.json()
        
        if "choices" in data and len(data["choices"]) > 0:
            return data["choices"][0]["message"]["content"]
        else:
            return "LLM调用失败"
    except Exception as e:
        print(f"LLM调用失败: {e}")
        return f"LLM调用失败: {str(e)}"


def run_ragas_evaluation(questions: List[str], contexts: List[List[str]], 
                         answers: List[str], ground_truths: List[str]) -> Dict[str, Any]:
    """运行RAGAS评估"""
    
    # 构建评估数据
    eval_data = {
        "question": questions,
        "context": contexts,
        "answer": answers,
        "ground_truth": ground_truths
    }
    
    # 创建Dataset
    dataset = Dataset.from_dict(eval_data)
    
    # 运行评估
    result = evaluate(
        dataset=dataset,
        metrics=[
            faithfulness,
            answer_relevancy,
            context_precision,
            context_recall,
            answer_correctness
        ]
    )
    
    return result


def main():
    print("=" * 50)
    print("RAGAS RAG系统评估")
    print("=" * 50)
    
    # 1. 加载测试数据
    print("\n[1/4] 加载测试数据...")
    test_data = load_test_data(TEST_DATA_PATH)
    print(f"    加载了 {len(test_data)} 条测试数据")
    
    # 2. 对每条测试数据执行RAG流程
    print("\n[2/4] 执行RAG流程（检索+生成）...")
    
    questions = []
    contexts = []
    answers = []
    ground_truths = []
    
    for i, item in enumerate(test_data):
        question = item.get("question", "")
        ground_truth = item.get("answer", "")
        
        if not question:
            continue
            
        # 检索
        retrieved_context = search_knowledge(question)
        
        # 生成回答
        generated_answer = generate_answer(question, retrieved_context)
        
        questions.append(question)
        contexts.append(retrieved_context)
        answers.append(generated_answer)
        ground_truths.append(ground_truth)
        
        if (i + 1) % 10 == 0:
            print(f"    已处理 {i + 1}/{len(test_data)} 条数据...")
    
    print(f"    完成处理，共 {len(questions)} 条有效数据")
    
    # 3. 运行RAGAS评估
    print("\n[3/4] 运行RAGAS评估...")
    result = run_ragas_evaluation(questions, contexts, answers, ground_truths)
    
    # 4. 输出结果
    print("\n[4/4] 评估结果:")
    print("=" * 50)
    
    metrics_df = result.to_pandas()
    print(metrics_df.to_string())
    
    # 计算平均分
    print("\n" + "=" * 50)
    print("平均指标分数:")
    print("=" * 50)
    for col in metrics_df.columns:
        if col != "question":
            avg = metrics_df[col].mean()
            print(f"  {col}: {avg:.4f}")
    
    # 保存详细结果
    output_path = "ragas_evaluation_results.csv"
    metrics_df.to_csv(output_path, index=False, encoding='utf-8-sig')
    print(f"\n详细结果已保存到: {output_path}")


if __name__ == "__main__":
    main()