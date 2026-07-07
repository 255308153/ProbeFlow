状态：ready-for-agent

# Issue 06：Test case coverage evaluator

## Parent

V2 Phase 8：Agent Evaluation Harness PRD

## What to build

在 Agent Evaluation Harness 中增加 Test case coverage evaluator。系统需要评估生成的 TestCaseDraft 或 TestCase 是否覆盖 golden fixture 指定的正常路径、参数校验、鉴权负例、边界值、业务规则、SUITE 依赖，以及是否存在重复用例。

这个 issue 要证明用例生成质量可以被结构化评估，而不是只看生成了多少条。

## Acceptance criteria

- [ ] golden fixture 可以声明 expected coverage categories。
- [ ] golden fixture 可以声明 required happy path scenario。
- [ ] golden fixture 可以声明 required validation negative cases。
- [ ] golden fixture 可以声明 required auth negative cases。
- [ ] golden fixture 可以声明 required boundary value cases。
- [ ] golden fixture 可以声明 required business rule cases。
- [ ] golden fixture 可以声明 required suite dependency coverage when applicable。
- [ ] evaluator 能消费生成后的 TestCaseDraft、TestCase 或等价测试资产结构。
- [ ] evaluator 能识别 request variation、assertion definition、scenario name、tags 或等价覆盖证据。
- [ ] evaluator 能对重复用例或高度相似用例给出 penalty。
- [ ] evaluator 能说明缺失的覆盖类别，而不是只给总分。
- [ ] evaluator 不要求真实 HTTP 执行通过；本 issue 只评估生成资产的覆盖质量。
- [ ] 测试覆盖 happy path、validation negative case、auth negative case、boundary value、business rule、suite dependency 和 duplicate penalty。

## Blocked by

- Issue 01：Evaluation harness contract, dataset and smoke report foundation
